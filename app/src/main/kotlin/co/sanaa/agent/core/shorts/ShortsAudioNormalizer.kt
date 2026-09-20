package co.sanaa.agent.core.shorts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Keep original video packets and soundtrack; pad only its missing audio tail with silence. */
object ShortsAudioNormalizer {
    fun normalize(source: File): File {
        val tracks=ShortsMediaInspector.inspect(source)
        require(tracks.usable)
        if(!tracks.audioEndsEarly) return source
        val output=File(source.parentFile,source.nameWithoutExtension+"-full.mp4")
        if(output.exists()) output.delete()
        val extractor=MediaExtractor()
        var decoder: MediaCodec?=null
        var encoder: MediaCodec?=null
        var muxer: MediaMuxer?=null
        var muxerStarted=false
        var success=false
        val deadline=android.os.SystemClock.elapsedRealtime()+120_000L
        fun bounded() { check(android.os.SystemClock.elapsedRealtime()<deadline) { "Audio normalization timed out" } }
        try {
            extractor.setDataSource(source.absolutePath)
            val ai=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val vi=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            val inputFormat=extractor.getTrackFormat(ai)
            extractor.selectTrack(ai)
            val dec=MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!);decoder=dec
            dec.configure(inputFormat,null,null,0);dec.start()
            var rate=inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcm=ByteArrayOutputStream()
            val info=MediaCodec.BufferInfo()
            var inputEnd=false;var outputEnd=false
            while(!outputEnd) {
                bounded()
                if(!inputEnd) {
                    val slot=dec.dequeueInputBuffer(10_000)
                    if(slot>=0) {
                        val buffer=dec.getInputBuffer(slot)!!;buffer.clear()
                        val count=extractor.readSampleData(buffer,0)
                        if(count<0) { dec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true }
                        else { dec.queueInputBuffer(slot,0,count,extractor.sampleTime,0);extractor.advance() }
                    }
                }
                val slot=dec.dequeueOutputBuffer(info,10_000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate=dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels=dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if(dec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        check(dec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)==android.media.AudioFormat.ENCODING_PCM_16BIT)
                } else if(slot>=0) {
                    val buffer=dec.getOutputBuffer(slot)!!
                    buffer.position(info.offset);buffer.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size);buffer.get(bytes);pcm.write(bytes)
                    check(pcm.size()<=64*1024*1024) { "Decoded soundtrack exceeds memory limit" }
                    outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    dec.releaseOutputBuffer(slot,false)
                }
            }
            dec.stop();dec.release();decoder=null
            require(rate in 8000..96000 && channels in 1..2)
            val samples=(tracks.videoDurationUs*rate+999_999)/1_000_000
            val targetBytes=samples*channels*2
            require(targetBytes<=64*1024*1024)
            val decoded=pcm.toByteArray()
            val enc=MediaCodec.createEncoderByType("audio/mp4a-latm");encoder=enc
            enc.configure(MediaFormat.createAudioFormat("audio/mp4a-latm",rate,channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,128000)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);enc.start()
            val writer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
            val videoTrack=writer.addTrack(extractor.getTrackFormat(vi));var audioTrack=-1
            var position=0L;inputEnd=false;outputEnd=false
            while(!outputEnd) {
                bounded()
                if(!inputEnd) {
                    val slot=enc.dequeueInputBuffer(10_000)
                    if(slot>=0) {
                        val buffer=enc.getInputBuffer(slot)!!;buffer.clear()
                        val count=minOf(buffer.remaining().toLong(),targetBytes-position).toInt().let { it-it%(channels*2) }
                        val pts=position/(channels*2)*1_000_000L/rate
                        if(count==0) { enc.queueInputBuffer(slot,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true }
                        else {
                            val bytes=ByteArray(count)
                            val available=minOf(count,(decoded.size-position).coerceAtLeast(0).toInt())
                            if(available>0) System.arraycopy(decoded,position.toInt(),bytes,0,available)
                            buffer.put(bytes);enc.queueInputBuffer(slot,0,count,pts,0);position+=count
                        }
                    }
                }
                val slot=enc.dequeueOutputBuffer(info,10_000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    audioTrack=writer.addTrack(enc.outputFormat);writer.start();muxerStarted=true
                } else if(slot>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        check(muxerStarted)
                        val buffer=enc.getOutputBuffer(slot)!!;buffer.position(info.offset);buffer.limit(info.offset+info.size)
                        writer.writeSampleData(audioTrack,buffer,info)
                    }
                    outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;enc.releaseOutputBuffer(slot,false)
                }
            }
            extractor.unselectTrack(ai);extractor.selectTrack(vi);extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val videoBuffer=ByteBuffer.allocate(4*1024*1024)
            while(true) {
                bounded();videoBuffer.clear();val size=extractor.readSampleData(videoBuffer,0);if(size<0)break
                info.set(0,size,extractor.sampleTime,extractor.sampleFlags);writer.writeSampleData(videoTrack,videoBuffer,info);extractor.advance()
            }
            writer.stop();muxerStarted=false;writer.release();muxer=null
            check(ShortsMediaInspector.inspect(output).let { it.usable && !it.audioEndsEarly }) { "Normalized soundtrack duration is still shorter than video" }
            success=true;return output
        } finally {
            runCatching { decoder?.stop() };decoder?.release()
            runCatching { encoder?.stop() };encoder?.release();extractor.release()
            if(muxerStarted) runCatching { muxer?.stop() };muxer?.release()
            if(!success) output.delete()
        }
    }
}

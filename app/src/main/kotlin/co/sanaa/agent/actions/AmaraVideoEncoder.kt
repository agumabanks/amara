package co.sanaa.agent.actions

import android.graphics.*
import android.media.*
import co.sanaa.agent.modules.AmaraAdSpec
import java.io.File

/** Hardware AVC encoding with bounded duration, timeout and memory; works without a server renderer. */
object AmaraVideoEncoder {
    fun render(output: File, ad: AmaraAdSpec, encodedPhotos: List<ByteArray>) {
        require(encodedPhotos.size in 1..4)
        val photos=mutableListOf<Bitmap>()
        var codec: MediaCodec?=null;var muxer: MediaMuxer?=null;var started=false;var complete=false
        val frame=Bitmap.createBitmap(AmaraMotionScene.WIDTH,AmaraMotionScene.HEIGHT,Bitmap.Config.ARGB_8888)
        try {
            encodedPhotos.forEach { bytes ->
                val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                require(opts.outWidth>0 && opts.outHeight>0)
                opts.inSampleSize=1
                while(maxOf(opts.outWidth,opts.outHeight)/opts.inSampleSize>1440) opts.inSampleSize*=2
                opts.inJustDecodeBounds=false
                photos+=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts))
            }
            val scene=AmaraMotionScene(ad,photos);val canvas=Canvas(frame)
            val width=frame.width;val height=frame.height
            val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE,3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE,AmaraMotionScene.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            }
            val encoder=MediaCodec.createEncoderByType("video/avc");codec=encoder
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            val writer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=writer
            val info=MediaCodec.BufferInfo();var track=-1;var inputDone=false;var index=0
            val total=AmaraMotionScene.SECONDS*AmaraMotionScene.FPS
            val pixels=IntArray(width*height)
            val deadline=android.os.SystemClock.elapsedRealtime()+180_000L
            while(!complete) {
                check(android.os.SystemClock.elapsedRealtime()<deadline) { "Ad video encoding timed out" }
                if(!inputDone) {
                    val slot=encoder.dequeueInputBuffer(10_000)
                    if(slot>=0) {
                        if(index==total) {
                            encoder.queueInputBuffer(slot,0,0,index*1_000_000L/AmaraMotionScene.FPS,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true
                        } else {
                            scene.draw(canvas,index.toFloat()/AmaraMotionScene.FPS)
                            frame.getPixels(pixels,0,width,0,0,width,height)
                            val input=requireNotNull(encoder.getInputImage(slot)) { "Encoder has no flexible YUV input" }
                            try { fillYuv(input,pixels,width,height) } finally { input.close() }
                            encoder.queueInputBuffer(slot,0,width*height*3/2,index*1_000_000L/AmaraMotionScene.FPS,0)
                            index++
                        }
                    }
                }
                var out=encoder.dequeueOutputBuffer(info,10_000)
                while(out!=MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!started);track=writer.addTrack(encoder.outputFormat);writer.start();started=true
                    } else if(out>=0) {
                        val bytes=requireNotNull(encoder.getOutputBuffer(out))
                        if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.size>0) {
                            check(started);bytes.position(info.offset);bytes.limit(info.offset+info.size)
                            writer.writeSampleData(track,bytes,info)
                        }
                        complete=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        encoder.releaseOutputBuffer(out,false)
                    }
                    if(complete) break
                    out=encoder.dequeueOutputBuffer(info,0)
                }
            }
            writer.stop();started=false
            check(output.length() in 1..40L*1024*1024) { "Ad video size invalid" }
        } finally {
            runCatching { codec?.stop() };codec?.release()
            if(started) runCatching { muxer?.stop() };muxer?.release()
            frame.recycle();photos.forEach(Bitmap::recycle)
            if(!complete) output.delete()
        }
    }
    private fun fillYuv(image: Image, pixels: IntArray, width: Int, height: Int) {
        val y=image.planes[0];val u=image.planes[1];val v=image.planes[2]
        val yb=y.buffer;val ub=u.buffer;val vb=v.buffer
        val yr=y.rowStride;val yp=y.pixelStride
        val ur=u.rowStride;val up=u.pixelStride
        val vr=v.rowStride;val vp=v.pixelStride
        // Bulk row transfers avoid millions of JNI ByteBuffer writes per ad.
        // Read chroma padding first: some codecs expose overlapping U/V planes.
        val yRow=ByteArray((width-1)*yp+1)
        val uRow=ByteArray((width/2-1)*up+1)
        val vRow=ByteArray((width/2-1)*vp+1)
        val us=ByteArray(width/2);val vs=ByteArray(width/2)
        for(row in 0 until height) {
            if(yp!=1) { yb.position(row*yr);yb.get(yRow) }
            for(col in 0 until width) {
                val rgb=pixels[row*width+col];val r=(rgb shr 16) and 255;val g=(rgb shr 8) and 255;val b=rgb and 255
                yRow[col*yp]=(((66*r+129*g+25*b+128) shr 8)+16).coerceIn(0,255).toByte()
                if(row%2==0 && col%2==0) {
                    us[col/2]=(((-38*r-74*g+112*b+128) shr 8)+128).coerceIn(0,255).toByte()
                    vs[col/2]=(((112*r-94*g-18*b+128) shr 8)+128).coerceIn(0,255).toByte()
                }
            }
            yb.position(row*yr);yb.put(yRow)
            if(row%2==0) {
                ub.position(row/2*ur);ub.get(uRow)
                for(col in us.indices) uRow[col*up]=us[col]
                ub.position(row/2*ur);ub.put(uRow)
                vb.position(row/2*vr);vb.get(vRow)
                for(col in vs.indices) vRow[col*vp]=vs[col]
                vb.position(row/2*vr);vb.put(vRow)
            }
        }
    }
}

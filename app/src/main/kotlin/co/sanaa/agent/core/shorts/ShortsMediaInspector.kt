package co.sanaa.agent.core.shorts

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Inspect the exported container instead of assuming TikTok saved the soundtrack. */
object ShortsMediaInspector {
    data class Tracks(val videoDurationUs: Long, val audioDurationUs: Long, val width: Int, val height: Int) {
        val usable: Boolean get() = videoDurationUs in 1..180_000_000L && audioDurationUs > 0 &&
            width > 0 && height >= width
        // Some TikTok exports have audio shorter than video. Preserve all video frames
        // by padding the audio tail before import if YouTube trims to the shorter track.
        val audioEndsEarly: Boolean get() = audioDurationUs + 100_000L < videoDurationUs
    }

    fun inspect(file: File): Tracks {
        require(file.isFile && file.length() in 1..100L * 1024 * 1024)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = 0L; var audio = 0L; var width = 0; var height = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                if (mime.startsWith("video/")) {
                    video = maxOf(video, duration)
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                } else if (mime.startsWith("audio/")) audio = maxOf(audio, duration)
            }
            return Tracks(video, audio, width, height)
        } finally { extractor.release() }
    }
}

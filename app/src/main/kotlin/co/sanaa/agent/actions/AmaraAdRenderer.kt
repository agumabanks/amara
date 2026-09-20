package co.sanaa.agent.actions

import android.graphics.*
import co.sanaa.agent.modules.AmaraAdSpec
import java.io.ByteArrayOutputStream

/** Deterministic phone-local artwork. Text stays inside a conservative portrait inset. */
object AmaraAdRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    fun render(photo: ByteArray, ad: AmaraAdSpec): ByteArray {
        val source = requireNotNull(BitmapFactory.decodeByteArray(photo, 0, photo.size)) { "Ad photo cannot be decoded" }
        val output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.scale(WIDTH/720f,HEIGHT/1280f)
            AmaraMotionScene(ad.copy(format="photo"),listOf(source)).draw(canvas,1f)
            return ByteArrayOutputStream().use { stream ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 94, stream)) { "Ad encoding failed" }
                stream.toByteArray()
            }
        } finally { source.recycle(); output.recycle() }
    }
}

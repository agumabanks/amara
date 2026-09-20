package co.sanaa.agent.actions

import android.graphics.*
import co.sanaa.agent.modules.AmaraAdSpec
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmaraAdRendererTest {
    @Test fun rendersPortraitWithFullPhotoAndReadableContactPanel() {
        val source = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        val bytes = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val rendered = AmaraAdRenderer.render(bytes, AmaraAdSpec("Office Chair", "UGX 120,000", "+256700000001", "Sanaa Media"))
        val output = BitmapFactory.decodeByteArray(rendered, 0, rendered.size)
        assertEquals(1080, output.width); assertEquals(1920, output.height)
        assertTrue(Color.red(output.getPixel(480, 750)) > 220)
        assertTrue(Color.green(output.getPixel(850, 1450)) > 160)
        val dir = java.io.File("build/reports/ad-preview").apply { mkdirs() }
        java.io.File(dir, "amara-ad.jpg").writeBytes(rendered)
        source.recycle(); output.recycle()
    }
    @Test fun corruptPhotoFailsInsteadOfSendingGenericArtwork() {
        assertThrows(IllegalArgumentException::class.java) {
            AmaraAdRenderer.render(byteArrayOf(1), AmaraAdSpec("Chair", "Ask for a quote", null, "Sanaa Media"))
        }
    }
}

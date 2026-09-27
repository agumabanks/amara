package co.sanaa.agent.actions
import android.graphics.*
import co.sanaa.agent.modules.AmaraAdSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmaraMotionSceneTest {
    @Test fun realCataloguePreviewWhenLocalFixtureIsAvailable() {
        val dir=File("../artifacts/defence-migration-20260909")
        val files=(0..3).map { File(dir,"gallery-$it.img") }
        if(files.any { !it.exists() }) return
        val photos=files.map { requireNotNull(BitmapFactory.decodeFile(it.path)) }
        val frame=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);val canvas=Canvas(frame)
        val ad=AmaraAdSpec("Date Stamp","UGX 85,000",null,"Sanaa Media",template="editorial")
        val scene=AmaraMotionScene(ad,photos)
        val out=File("build/reports/real-ad-preview").apply { mkdirs() }
        for(t in listOf(1f,3f,6f,9f,11f)) {
            scene.draw(canvas,t)
            File(out,"frame-${t.toInt()}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        frame.recycle();photos.forEach(Bitmap::recycle)
    }
    @Test fun headlineIsFullyVisibleAtFirstFrameAndThroughoutFirstThreeSeconds() {
        val photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        for (title in listOf("Date Stamp", "Smart Business Cards", "Solar Water Pump")) {
            val scene=AmaraMotionScene(AmaraAdSpec(title,"Ask for a quote",null,"Sanaa Media"),listOf(photo))
            val first=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
            val later=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
            scene.draw(Canvas(first),0f); scene.draw(Canvas(later),3f)
            fun darkPixels(frame: Bitmap) = (166 until 310).sumOf { y ->
                (54 until 598).count { x -> frame.getPixel(x,y)==AmaraMotionScene.INK }
            }
            assertTrue("Headline must have substantial visible ink: $title",darkPixels(first)>2000)
            assertEquals(darkPixels(first),darkPixels(later))
            val out=File("build/reports/headline-preview").apply { mkdirs() }
            File(out,title.replace(' ','-')+".png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG,100,it) }
            first.recycle();later.recycle()
        }
        photo.recycle()
    }
    @Test fun timelineAlternatesContactAndShowsGalleryBeforeSharedClosing() {
        assertTrue(AmaraMotionScene.ctaWhatsApp(1f,true))
        assertFalse(AmaraMotionScene.ctaWhatsApp(4f,true))
        assertTrue(AmaraMotionScene.ctaWhatsApp(7f,true))
        assertFalse(AmaraMotionScene.ctaWhatsApp(1f,false))
        assertEquals(0,AmaraMotionScene.galleryIndex(1f,3))
        assertEquals(2,AmaraMotionScene.galleryIndex(8f,3))
        val photo=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val frame=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(frame)
        for(style in listOf("editorial","showcase","poster")) {
            val scene=AmaraMotionScene(AmaraAdSpec("Date Stamp","UGX 65,000","+256700000001","Sanaa Media",template=style),listOf(photo))
            val dir=File("build/reports/motion-preview/$style").apply { mkdirs() }
            for(t in listOf(1f,4f,7f,11f)) {
                scene.draw(canvas,t)
                File(dir,"frame-${t.toInt()}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
                if(t==1f) assertTrue(Color.red(frame.getPixel(320,520))>200)
                if(t==11f) assertEquals(AmaraMotionScene.INK,frame.getPixel(10,10))
            }
        }
        frame.recycle();photo.recycle()
    }
}

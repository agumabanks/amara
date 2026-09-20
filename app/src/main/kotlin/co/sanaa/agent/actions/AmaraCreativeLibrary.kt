package co.sanaa.agent.actions

import co.sanaa.agent.modules.AmaraAdSpec
import java.io.File

/** Reusable artwork is separate from each publication's immutable evidence binding. */
object AmaraCreativeLibrary {
    fun prepare(root: File, imageUrl: String, ad: AmaraAdSpec, render: () -> ByteArray): File {
        val key=BoundTikTokMedia.sha256((imageUrl+ad.toJson().toString()).toByteArray())
        val reused=File(root,BoundTikTokMedia.sha256(key.toByteArray())+".binding").exists()
        val started=android.os.SystemClock.elapsedRealtime()
        val file=BoundTikTokMedia.prepare(root,key,extension=if(ad.format=="photo") "jpg" else "mp4",
            maxBytes=40*1024*1024,download=render)
        android.util.Log.i("AmaraCreativeLibrary","format=${ad.format}; reused=$reused; elapsed_ms=${android.os.SystemClock.elapsedRealtime()-started}; media=${file.name}")
        return file
    }
}

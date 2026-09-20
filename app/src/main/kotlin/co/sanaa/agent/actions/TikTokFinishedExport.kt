package co.sanaa.agent.actions

import android.content.Context
import android.provider.MediaStore
import android.content.ContentUris
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlinx.coroutines.delay

/** Download only after exact own-profile content verification; never pick a random gallery video. */
internal class TikTokFinishedExport(private val context: Context, private val actions: AccessibilityActions) {
    private fun candidates(): Set<Long> = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),null,null,null)?.use { c -> buildSet { while(c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
    suspend fun capture(caption: String, key: String): File {
        val directory=File(context.filesDir,"youtube-bound-media").apply { mkdirs() }
        val target=File(directory,BoundTikTokMedia.sha256(key.toByteArray())+".mp4")
        val manifest=File(directory,target.name+".sha256")
        if(target.exists() && manifest.exists()) {
            check(BoundTikTokMedia.sha256(target.readBytes())==manifest.readText()) { "Saved Shorts media changed" }
            return target
        }
        val permission=if(Build.VERSION.SDK_INT>=33) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        check(ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED) {
            "Video access is required to retrieve TikTok's finished export; grant it in Android app permissions"
        }
        check(actions.openAppByName("tiktok")) { "TikTok is unavailable" }
        check(actions.openVerifiedTikTokSource(caption) && actions.observeTikTokPublishedCaption(caption).verified) {
            "The verified queued ad was not found among the recent own-profile posts; review the source before export"
        }
        val before=candidates()
        check(actions.clickLabel("Share video")) { "TikTok's post share control is unavailable" }
        check(TikTokExportControlWait.awaitDownload(
            allowed = { co.sanaa.agent.core.OwnerPower(context).isOn() && actions.isAvailable() },
            packageName = { actions.snapshot().packageName },
            tapDownload = { actions.clickExactLabel("Download", "Save video") },
            pause = { delay(500) },
        )) { "TikTok download control did not become available within 12 seconds; no YouTube upload dispatched" }
        repeat(60) {
            delay(1000)
            val new=candidates()-before
            check(new.size<=1) { "Multiple new gallery videos make this export ambiguous" }
            if(new.size==1) {
                val candidateUri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new.single())
                if(Build.VERSION.SDK_INT>=29) {
                    val owner=context.contentResolver.query(candidateUri,arrayOf(MediaStore.Video.Media.OWNER_PACKAGE_NAME),null,null,null)?.use {
                        if(it.moveToFirst()) it.getString(0) else null
                    }
                    check(owner==TikTokSoundSelection.PACKAGE) { "New media was not created by TikTok; export is unproven" }
                }
                val uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new.single())
                val temp=File(directory,target.name+".partial")
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { out ->
                        val buffer=ByteArray(65536);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0)break;total+=n;check(total<=100L*1024*1024);out.write(buffer,0,n) }
                    } }
                    check(co.sanaa.agent.core.shorts.ShortsMediaInspector.inspect(temp).usable)
                    check(temp.renameTo(target))
                    manifest.writeText(BoundTikTokMedia.sha256(target.readBytes()))
                }.onSuccess { return target }
                temp.delete()
            }
        }
        error("TikTok export did not produce a complete video with sound")
    }
}

package co.sanaa.agent.actions

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** A fresh importer task prevents Android from restoring TikTok's existing feed task. */
internal object TikTokShareIntent {
    fun create(uri: Uri, mimeType: String, caption: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        clipData = ClipData.newRawUri("Ad", uri)
        setPackage("com.zhiliaoapp.musically")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

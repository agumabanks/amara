package co.sanaa.agent.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Explicit owner-selected document tree, not unrestricted shared storage. */
class SokoSharedFolder(private val context: Context) {
    private val prefs = context.getSharedPreferences("soko_shared_folder", Context.MODE_PRIVATE)
    fun save(uri: Uri) {
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission })
        check(prefs.edit().putString("tree", uri.toString()).commit())
    }
    fun isReady(): Boolean = prefs.getString("tree", null)?.let { value ->
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == value && it.isWritePermission }
    } == true

    /** Export retained prepared media only. Never exports chat, credentials or ledgers. */
    fun exportPreparedMedia(): Int {
        check(OwnerPower(context).isOn()) { "Turn Amara on before exporting files" }
        check(isReady()) { "Choose a shared folder first" }
        val tree = Uri.parse(prefs.getString("tree", null)!!)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = mutableSetOf<String>()
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,
            DocumentsContract.getTreeDocumentId(tree)), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) existing.add(c.getString(0))
        }
        var count = 0
        for (folder in listOf("whatsapp-bound-media", "tiktok-bound-media", "tiktok-bound-video")) {
            for (file in java.io.File(context.filesDir, folder).listFiles().orEmpty().filter { it.isFile }.take(50)) {
                val mime = when (file.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mp4" -> "video/mp4"; else -> continue }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val name = "amara-$hash.${file.extension.lowercase()}"
                if (name in existing) continue
                val destination = DocumentsContract.createDocument(context.contentResolver, parent, mime, ".partial-${java.util.UUID.randomUUID()}-$name") ?: error("Could not create shared media")
                try {
                    context.contentResolver.openOutputStream(destination)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Could not write shared media")
                    check(DocumentsContract.renameDocument(context.contentResolver, destination, name) != null) { "Could not finalize shared media" }
                } catch (error: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                    throw error
                }
                existing.add(name)
                count++
            }
        }
        return count
    }
}

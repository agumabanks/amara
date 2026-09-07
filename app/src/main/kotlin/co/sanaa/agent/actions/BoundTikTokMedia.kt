package co.sanaa.agent.actions

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Freeze exact normalized bytes before sharing; retries never re-download a mutable URL. */
object BoundTikTokMedia {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun prepare(directory: File, bindingKey: String, download: () -> ByteArray): File {
        require(bindingKey.isNotBlank())
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create TikTok media store" }
        val manifest = AtomicFile(File(directory, sha256(bindingKey.toByteArray()) + ".binding"))
        if (manifest.baseFile.exists()) {
            val digest = manifest.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }
            check(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid TikTok media binding" }
            val image = File(directory, "$digest.jpg")
            check(image.isFile && image.length() in 1..15L * 1024 * 1024) { "Bound TikTok media missing" }
            check(sha256(image.readBytes()) == digest) { "Bound TikTok media changed; review required" }
            return image
        }
        val bytes = download()
        check(bytes.size in 1..15 * 1024 * 1024) { "TikTok media exceeds limit" }
        val digest = sha256(bytes)
        val image = File(directory, "$digest.jpg")
        if (!image.exists()) {
            check(directory.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= 128L * 1024 * 1024) {
                "TikTok media evidence store full; review retained publications"
            }
            write(AtomicFile(image), bytes)
        }
        check(sha256(image.readBytes()) == digest) { "TikTok media integrity check failed" }
        write(manifest, digest.toByteArray(Charsets.US_ASCII))
        return image
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}

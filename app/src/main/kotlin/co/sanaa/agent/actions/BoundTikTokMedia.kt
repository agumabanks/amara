package co.sanaa.agent.actions

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Freeze exact normalized bytes before sharing; retries never re-download a mutable URL. */
object BoundTikTokMedia {
    // Owner asked for sustained autonomous posting; 512 MiB stays under 1% of the
    // OPPO test device's free disk while bounding unproven prepared-media growth.
    const val LIMIT_BYTES = 512L * 1024 * 1024

    /** Store is at capacity even after automatic reclaim offered everything provably terminal. */
    class MediaStoreFullException(message: String) : IllegalStateException(message)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun prepare(directory: File, bindingKey: String, extension: String = "jpg", maxBytes: Int = 15 * 1024 * 1024, storeLimitBytes: Long = LIMIT_BYTES, reclaimSpace: (store: File, neededBytes: Long) -> Long = { _, _ -> 0L }, download: () -> ByteArray): File {
        require(bindingKey.isNotBlank())
        require(extension in setOf("jpg", "mp4") && maxBytes in 1..40 * 1024 * 1024)
        require(storeLimitBytes in 1..LIMIT_BYTES) { "Store capacity cannot exceed the owner-set limit" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create TikTok media store" }
        val bindingHash = sha256(bindingKey.toByteArray())
        val retirementStores = if (directory.name in setOf("tiktok-bound-media", "tiktok-bound-video")) {
            listOf(File(directory.parentFile, "tiktok-bound-media"), File(directory.parentFile, "tiktok-bound-video"))
        } else listOf(directory)
        check(retirementStores.none { store ->
            listOf(".reclaimed", ".reclaimed.bak", ".reclaimed.new").any { File(store, bindingHash + it).exists() }
        }) {
            "Bound TikTok media was reclaimed by owner; this binding cannot be reused"
        }
        val manifest = AtomicFile(File(directory, "$bindingHash.binding"))
        if (manifest.baseFile.exists() || File(directory, "$bindingHash.binding.bak").exists()) {
            val digest = manifest.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }
            check(digest.matches(Regex("[a-f0-9]{64}"))) { "Invalid TikTok media binding" }
            val image = File(directory, "$digest.$extension")
            check(image.isFile && image.length() in 1..maxBytes.toLong()) { "Bound TikTok media missing" }
            check(sha256(image.readBytes()) == digest) { "Bound TikTok media changed; review required" }
            return image
        }
        check(!File(directory, "$bindingHash.binding.new").exists()) { "Incomplete TikTok media binding" }
        val bytes = download()
        check(bytes.size in 1..maxBytes) { "TikTok media exceeds limit" }
        val digest = sha256(bytes)
        val image = File(directory, "$digest.$extension")
        if (!image.exists()) {
            // Give the caller one bounded chance to free provably-terminal evidence
            // before refusing the write; the FS is re-read, never the hook's report.
            if (storeBytes(directory) + bytes.size > storeLimitBytes) reclaimSpace(directory, bytes.size.toLong())
            if (storeBytes(directory) + bytes.size > storeLimitBytes) {
                throw MediaStoreFullException("TikTok media evidence store full; review retained publications")
            }
            write(AtomicFile(image), bytes)
        }
        check(sha256(image.readBytes()) == digest) { "TikTok media integrity check failed" }
        write(manifest, digest.toByteArray(Charsets.US_ASCII))
        return image
    }

    @Synchronized
    internal fun reclaim(directory: File, file: File, bindingHashes: List<String>, digest: String): Boolean {
        check(Thread.holdsLock(this))
        bindingHashes.forEach { hash ->
            check(File(directory, "$hash.binding").readText(Charsets.US_ASCII) == digest)
            write(AtomicFile(File(directory, "$hash.reclaimed")), digest.toByteArray(Charsets.US_ASCII))
        }
        return file.delete()
    }

    private fun storeBytes(directory: File): Long =
        checkNotNull(directory.listFiles()) { "Cannot inspect TikTok media store" }.sumOf { it.length() }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}

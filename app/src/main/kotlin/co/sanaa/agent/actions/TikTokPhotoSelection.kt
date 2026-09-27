package co.sanaa.agent.actions

/** Only try images attached to the same signed Soko listing. */
object TikTokPhotoSelection {
    fun <T> decode(primary: String, gallery: List<String>, limit: Int, decoder: (String) -> T): List<T> {
        val images = mutableListOf<T>()
        var lastFailure: Exception? = null
        for (url in (listOf(primary) + gallery).filter(String::isNotBlank).distinct()) {
            if (images.size >= limit) break
            try {
                images += decoder(url)
            } catch (error: Exception) {
                if (error is InterruptedException || error is java.util.concurrent.CancellationException) throw error
                lastFailure = error
            }
        }
        if (images.isEmpty()) throw IllegalStateException("No decodable image in this Soko listing", lastFailure)
        return images
    }
}

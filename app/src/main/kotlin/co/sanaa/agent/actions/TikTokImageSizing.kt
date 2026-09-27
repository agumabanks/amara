package co.sanaa.agent.actions

/** Decode large catalogue photos at a bounded size before composing a TikTok creative. */
object TikTokImageSizing {
    private const val MAX_SOURCE_PIXELS = 160_000_000L
    private const val TARGET_PIXELS = 12_000_000L

    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0 && width <= 20_000 && height <= 20_000 &&
            width.toLong() * height <= MAX_SOURCE_PIXELS) { "Invalid or oversized TikTok image dimensions" }
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > TARGET_PIXELS) sample *= 2
        return sample
    }
}

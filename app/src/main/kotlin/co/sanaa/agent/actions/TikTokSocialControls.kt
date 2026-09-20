package co.sanaa.agent.actions

/** Observed TikTok resource variants; unknown controls remain unavailable. */
internal object TikTokSocialControls {
    private val aliases = mapOf(
        "efv" to listOf("eir", "ej4"),
        "f15" to listOf("f41", "f4d"),
        "ywb" to listOf("z55", "z9p"),
        "l8h" to listOf("lcl", "lej"),
        "eg4" to listOf("ej0", "ejc"),
        "cz_" to listOf("d1h"),
        "ekn" to listOf("enj", "enw"),
        "oeg" to listOf("ok0"),
    )
    fun ids(id: String): List<String> = listOf(id) + aliases[id].orEmpty()
}

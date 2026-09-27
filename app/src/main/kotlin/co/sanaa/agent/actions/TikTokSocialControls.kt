package co.sanaa.agent.actions

/** Observed TikTok resource variants; unknown controls remain unavailable. */
internal object TikTokSocialControls {
    private val aliases = mapOf(
        "profile_name" to listOf("t0u", "su7", "t4g", "t7l"),
        "global_search" to listOf("k9z", "k_8"),
        "efv" to listOf("eir", "ej4"),
        "f15" to listOf("f41", "f4d", "f4t"),
        "ywb" to listOf("z55", "z9p"),
        "l8h" to listOf("lcl", "lej", "v_touch_area"),
        "eg4" to listOf("ej0", "ejc", "ejs"),
        "cz_" to listOf("d1h", "d1m"),
        "ekn" to listOf("enj", "enw", "eob"),
        "comment_header" to listOf("wk7"),
        "oeg" to listOf("ok0"),
    )
    fun ids(id: String): List<String> = listOf(id) + aliases[id].orEmpty()
}

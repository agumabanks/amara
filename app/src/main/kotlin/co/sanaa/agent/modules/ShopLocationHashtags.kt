package co.sanaa.agent.modules

/** Only literal places in the shop's public address; no location inferred from customers or device GPS. */
object ShopLocationHashtags {
    fun fromAddress(address: String): List<String> {
        val clean=address.replace(Regex("<[^>]+>")," ").replace(Regex("\\s+")," ").trim()
        if(clean.isBlank() || clean.equals("null",true)) return emptyList()
        val known=listOf("Nasser Road","Kampala","Nakasero","Wandegeya","Ntinda","Nakawa","Kawempe","Makindye",
            "Kabalagala","Kansanga","Kisaasi","Bukoto","Kololo","Bugolobi","Muyenga","Entebbe","Wakiso","Mukono",
            "Jinja","Mbarara","Gulu","Mbale","Masaka","Fort Portal","Arua","Lira","Hoima","Uganda")
        return known.filter { place -> Regex("(?i)(?<![\\p{L}\\p{N}])"+Regex.escape(place)+"(?![\\p{L}\\p{N}])").containsMatchIn(clean) }
            .take(3).map { "#"+it.replace(" ","") }
    }
}

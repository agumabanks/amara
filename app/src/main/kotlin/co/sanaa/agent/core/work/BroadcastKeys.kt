package co.sanaa.agent.core.work

import co.sanaa.agent.core.ContentHashing

/** Day-scoped exactly-once keys for standing-policy broadcasts (DECISIONS D-007). */
object BroadcastKeys {
    fun status(day: String, message: String): String =
        "morning-status:$day:${ContentHashing.hash(message)}"

    fun group(day: String, group: String, message: String): String =
        "morning-group:$day:$group:${ContentHashing.hash(message)}"

    fun tiktok(day: String, listingId: String, caption: String): String =
        "morning-tiktok:$day:$listingId:${ContentHashing.hash(caption)}"
}

val broadcastKeysMarker: String = "present"

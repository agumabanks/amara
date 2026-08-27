package co.sanaa.agent.api

import org.json.JSONObject

data class SokoListing(
    val id: String,
    val title: String,
    val description: String,
    val priceUgx: Long,
    val category: String,
    val photoCount: Int,
    val viewCount: Int,
    val stock: Int?,
    val imageUrl: String?,
    val raw: JSONObject,
) {
    fun summary() = "$title — UGX ${"%,d".format(priceUgx)}"
}

data class SokoMessage(val id: String, val sender: String, val text: String, val threadId: String, val raw: JSONObject)

data class ModuleResult(val module: String, val success: Boolean, val summary: String, val error: String? = null, val metadata: Map<String, Any?> = emptyMap())

data class VerificationResult(val verified: Boolean, val detail: String)

package co.sanaa.agent.actions

import co.sanaa.agent.core.ContentHashing
import org.json.JSONObject
import java.io.File

/**
 * Golden Accessibility-hierarchy fixtures for verifier regression (Phase A3).
 * Fixtures are declarative JSON so new app-layout regressions become data, not code.
 *
 * Each fixture describes a node tree surface and the verdict the verifier logic must
 * produce. Tests load every fixture and assert the expected outcome, covering:
 * sent bubble, unsent draft, quoted message, stale earlier message, wrong chat,
 * wrong package, Status composer, published Status, TikTok draft, TikTok public post,
 * save succeeded, save failed, save uncertain.
 */
data class GoldenNode(
    val className: String,
    val editable: Boolean,
    val text: String = "",
    val contentDescription: String = "",
    /** Accessibility parent-container grouping; delivery markers bind within a row. */
    val row: Int = 0,
)

data class GoldenFixture(
    val fixture: String,
    val pkg: String,
    val targetVisible: Boolean = true,
    val nodes: List<GoldenNode>,
    val expectedVerified: Boolean,
    val expectedBlockerContains: String? = null,
    val minConfidence: Double = 0.0,
    /** True when the content bubble (and its marker) already existed before the action. */
    val preExistingContent: Boolean = false,
) {
    fun toNodeFacts(content: String): MessageNodeFacts {
        val wanted = ContentHashing.normalize(content)
        var inEditable = false
        var inReadOnly = false
        val contentRows = mutableSetOf<Int>()
        nodes.forEach { node ->
            val combined = ContentHashing.normalize(node.text + " " + node.contentDescription)
            if (wanted.isNotEmpty() && combined.contains(wanted)) {
                if (node.editable || node.className.endsWith("EditText")) inEditable = true else { inReadOnly = true; contentRows += node.row }
            }
        }
        // A delivery marker proves THIS message only when it lives in one of the same rows.
        val deliveryState = nodes.firstOrNull { n ->
            n.row in contentRows && listOf("Sent", "Delivered", "Read").any { marker ->
                n.contentDescription.equals(marker, true) || n.text.equals(marker, true)
            }
        }?.let { n -> listOf("Sent", "Delivered", "Read").first { m -> n.contentDescription.equals(m, true) || n.text.equals(m, true) } }
        return MessageNodeFacts(
            inReadOnlyBubble = inReadOnly,
            onlyInsideEditableField = inEditable && !inReadOnly,
            deliveryState = deliveryState,
        )
    }

    fun visibleLines(): List<String> = nodes.map { it.text }.filter { it.isNotBlank() }

    companion object {
        fun parse(json: String): GoldenFixture {
            val root = JSONObject(json)
            return GoldenFixture(
                fixture = root.getString("fixture"),
                pkg = root.getString("package"),
                targetVisible = root.optBoolean("targetVisible", true),
                nodes = root.getJSONArray("nodes").let { array ->
                    (0 until array.length()).map { index ->
                        val node = array.getJSONObject(index)
                        GoldenNode(
                            className = node.getString("class"),
                            editable = node.optBoolean("editable", false),
                            text = node.optString("text", ""),
                            contentDescription = node.optString("contentDescription", ""),
                            row = node.optInt("row", 0),
                        )
                    }
                },
                expectedVerified = root.getJSONObject("expected").getBoolean("verified"),
                expectedBlockerContains = root.getJSONObject("expected").optString("blockerContains").ifBlank { null },
                minConfidence = root.getJSONObject("expected").optDouble("minConfidence", 0.0),
                preExistingContent = root.optBoolean("preExistingContent", false),
            )
        }

        fun loadAll(directory: File): List<GoldenFixture> =
            directory.listFiles { file -> file.extension == "json" }.orEmpty()
                .map { file -> parse(file.readText()) }
                .sortedBy { it.fixture }
    }
}

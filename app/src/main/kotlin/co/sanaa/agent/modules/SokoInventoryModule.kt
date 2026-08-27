package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.SokoInventoryItem
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig

data class SokoInventoryResult(
    val success: Boolean,
    val summary: String,
    val items: List<SokoInventoryItem> = emptyList(),
)

/** Read-only Soko catalogue learning. It never opens an editor or changes a listing. */
class SokoInventoryModule(
    private val config: SecureConfig,
    private val actions: AccessibilityActions,
    private val memory: AmaraMemory,
    // Fail-closed default: credentials arrive ONLY via the injected vault-backed
    // authority; plaintext config is read nowhere outside the fenced migration.
    private val pin: () -> String = { "" },
) {
    suspend fun scan(): SokoInventoryResult {
        val scan = actions.crawlSokoInventory(pin())
        if (scan.failure != null) {
            memory.recordAction(
                TYPE, null, "Soko Terminal", "Read the Soko inventory", "Attempted a read-only catalogue scan.",
                scan.failure, null, false,
            )
            return SokoInventoryResult(false, scan.failure, scan.items)
        }
        if (scan.items.isEmpty()) {
            val result = "Soko Terminal was visible, but no product cards could be parsed safely."
            memory.recordAction(TYPE, null, "Soko Terminal", "Read the Soko inventory", "Read the visible catalogue screens.", result, null, false)
            return SokoInventoryResult(false, result)
        }

        val weak = scan.items.filter { it.weakReasons.isNotEmpty() }
        val catalogue = scan.items.take(12).joinToString("; ") { item ->
            "${item.name} — ${item.priceUgx?.let { "UGX $it" } ?: "price not shown"}, ${item.stockState}"
        }
        val remaining = (scan.items.size - 12).coerceAtLeast(0)
        val weakSummary = if (weak.isEmpty()) {
            "No weak title or missing-price issue was visible on these cards; descriptions require opening each listing and were not guessed."
        } else {
            "${weak.size} visible cards need attention: ${weak.joinToString("; ") { "${it.name} (${it.weakReasons.joinToString()})" }}."
        }
        val coverage = if (scan.reachedEnd) "reached the visible end" else "stopped at the safety limit"
        val result = "Read ${scan.items.size} unique products across ${scan.screensRead} screen states and $coverage; ignored ${scan.duplicateCardsIgnored} repeated cards. $weakSummary Products observed include: $catalogue${if (remaining > 0) "; plus $remaining more recorded in device memory." else "."}"
        memory.recordAction(
            TYPE, null, "Soko Terminal", "Read the Soko inventory", "Scanned and deduplicated the Soko catalogue without editing it.",
            result, null, true,
        )
        return SokoInventoryResult(true, result, scan.items)
    }

    companion object {
        private const val TYPE = "soko_inventory_scan"
    }
}

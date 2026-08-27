package co.sanaa.agent.modules

import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.SokoInventoryParser
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SokoCredentialAuditor

data class SokoSectionReport(
    val section: String,
    val summary: String,
    val items: List<String>,
    val failure: String? = null,
)

class SokoFullIntelligence(
    private val config: SecureConfig,
    private val actions: AccessibilityActions,
    private val memory: AmaraMemory,
    private val groq: GroqClient,
    /**
     * Vault-backed credential authority injected by AgentRuntime. Fail-closed
     * default; every inner module this class constructs shares it, so no path
     * can fall back to the plaintext config copy.
     */
    private val pin: () -> String = { "" },
    /** Login-outcome auditing shared with all constructed modules. */
    private val credentialAudit: SokoCredentialAuditor? = null,
) {
    suspend fun readDashboard(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Dashboard", "Could not open Soko Terminal", emptyList())
        if (actions.waitForForegroundPackage("com.soko24.soko_seller_terminal") == null) return SokoSectionReport("Dashboard", "Soko Terminal did not open", emptyList())
        kotlinx.coroutines.delay(1_500)
        if (!actions.recoverSokoHome("")) return SokoSectionReport("Dashboard", "Could not reach Soko home", emptyList())
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Dashboard", "Could not open menu", emptyList())
        if (!actions.scrollUntil("Dashboard", 5)) return SokoSectionReport("Dashboard", "Could not find Dashboard section", emptyList())
        if (!actions.clickExactLabel("Dashboard")) return SokoSectionReport("Dashboard", "Could not open Dashboard", emptyList())
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Sync data", "TODAY", "DAILY").contains(it.trim()) }
        memory.recordAction("soko_dashboard", null, "Soko Terminal", "Read Dashboard", "Read today's KPIs and sales pulse", "Dashboard screen read.", null, true)
        return SokoSectionReport("Dashboard", "Today's KPIs and sales pulse: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readCustomers(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Customers", "Could not open Soko Terminal", emptyList())
        if (!actions.recoverSokoHome("")) return SokoSectionReport("Customers", "Could not reach Soko home", emptyList())
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Customers", "Could not open menu", emptyList())
        if (!actions.clickExactLabel("Customers")) return SokoSectionReport("Customers", "Could not open Customers", emptyList())
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search", "Add customer").contains(it.trim()) }
        memory.recordAction("soko_customers", null, "Soko Terminal", "Read Customers", "Read customer contacts", "Customer list read.", null, true)
        return SokoSectionReport("Customers", "Customer contacts: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readOrders(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Orders", "Could not open Soko Terminal", emptyList())
        if (!actions.recoverSokoHome("")) return SokoSectionReport("Orders", "Could not reach Soko home", emptyList())
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Orders", "Could not open menu", emptyList())
        if (!actions.clickExactLabel("Orders")) return SokoSectionReport("Orders", "Could not open Orders", emptyList())
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search", "Filter").contains(it.trim()) }
        memory.recordAction("soko_orders", null, "Soko Terminal", "Read Orders", "Read marketplace orders", "Orders screen read.", null, true)
        return SokoSectionReport("Orders", "Orders: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readAlerts(): SokoSectionReport {
        // Same scoped vault authority as every other Soko login path — never a
        // parallel module wired to the plaintext config copy.
        val soko = SokoIntelligenceModule(config, actions, memory, groq, pin = pin, credentialAudit = credentialAudit)
        val result = soko.alertsNeedingAction()
        return SokoSectionReport("Alerts", result.summary, result.summary.split("; "))
    }

    suspend fun readProducts(): SokoSectionReport {
        val items = mutableListOf<String>()
        var screens = 0
        var reachedEnd = false
        var duplicates = 0
        var seen = setOf<String>()
        repeat(20) {
            val screen = actions.snapshot()
            val labels = screen.visibleText.filter { it.isNotBlank() }
            val parsed = SokoInventoryParser.parse(labels)
            val newItems = parsed.filter { item ->
                val key = item.name.lowercase().filter { it.isLetterOrDigit() }
                if (key in seen) {
                    duplicates++
                    false
                } else {
                    seen = seen + key
                    true
                }
            }
            items.addAll(newItems.map { "${it.name} — ${it.priceText.ifBlank { "price not shown" }} (${it.stockState})" })
            screens++
            if (!actions.scrollDown()) {
                reachedEnd = true
                return@repeat
            }
            actions.pause(co.sanaa.agent.core.InteractionKind.SCROLL_SETTLE)
        }
        memory.recordAction("soko_products", null, "Soko Terminal", "Read Products", "Read ${items.size} products across $screens screens", "Product scan complete.", null, true)
        return SokoSectionReport("Products", "${items.size} products across $screens screens${if (reachedEnd) " (reached end)" else ""}", items.take(15))
    }

    suspend fun readRefunds(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Refunds", "Could not open Soko Terminal", emptyList())
        if (!actions.recoverSokoHome("")) return SokoSectionReport("Refunds", "Could not reach Soko home", emptyList())
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Refunds", "Could not open menu", emptyList())
        if (!actions.clickExactLabel("Refunds")) return SokoSectionReport("Refunds", "Could not open Refunds", emptyList())
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search").contains(it.trim()) }
        memory.recordAction("soko_refunds", null, "Soko Terminal", "Read Refunds", "Read refunds and disputes", "Refunds screen read.", null, true)
        return SokoSectionReport("Refunds", "Refunds: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readSuppliers(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Suppliers", "Could not open Soko Terminal", emptyList())
        if (!actions.recoverSokoHome("")) return SokoSectionReport("Suppliers", "Could not reach Soko home", emptyList())
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Suppliers", "Could not open menu", emptyList())
        if (!actions.scrollUntil("Suppliers", 5)) return SokoSectionReport("Suppliers", "Could not find Suppliers", emptyList())
        if (!actions.clickExactLabel("Suppliers")) return SokoSectionReport("Suppliers", "Could not open Suppliers", emptyList())
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Add supplier").contains(it.trim()) }
        memory.recordAction("soko_suppliers", null, "Soko Terminal", "Read Suppliers", "Read supplier list", "Suppliers screen read.", null, true)
        return SokoSectionReport("Suppliers", "Suppliers: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun fullShopReport(): String {
        val sections = listOf(
            readDashboard(),
            readAlerts(),
            readOrders(),
            readCustomers(),
            readProducts(),
        )
        return sections.joinToString("\n\n") { report ->
            "${report.section}:\n${report.summary}"
        }
    }
}

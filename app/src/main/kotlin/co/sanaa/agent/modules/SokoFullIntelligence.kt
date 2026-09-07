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

data class SokoShopReport(val sections: List<SokoSectionReport>) {
    val success: Boolean get() = sections.isNotEmpty() && sections.all { it.failure == null }
    val summary: String get() = sections.joinToString("\n\n") { "${it.section}:\n${it.summary}" }
}

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
        if (!actions.openSokoTerminal()) return SokoSectionReport("Dashboard", "Could not open Soko Terminal", emptyList(), "Could not open Soko Terminal")
        if (actions.waitForForegroundPackage("com.soko24.soko_seller_terminal") == null) return SokoSectionReport("Dashboard", "Soko Terminal did not open", emptyList(), "Soko Terminal did not open")
        kotlinx.coroutines.delay(1_500)
        if (!recoverHome()) return SokoSectionReport("Dashboard", "Could not reach Soko home", emptyList(), "Could not reach Soko home")
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Dashboard", "Could not open menu", emptyList(), "Could not open menu")
        if (!actions.scrollUntil("Dashboard", 5)) return SokoSectionReport("Dashboard", "Could not find Dashboard section", emptyList(), "Could not find Dashboard section")
        if (!actions.clickExactLabel("Dashboard")) return SokoSectionReport("Dashboard", "Could not open Dashboard", emptyList(), "Could not open Dashboard")
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Sync data", "TODAY", "DAILY").contains(it.trim()) }
        memory.recordAction("soko_dashboard", null, "Soko Terminal", "Read Dashboard", "Read today's KPIs and sales pulse", "Dashboard screen read.", null, true)
        return SokoSectionReport("Dashboard", "Today's KPIs and sales pulse: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readCustomers(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Customers", "Could not open Soko Terminal", emptyList(), "Could not open Soko Terminal")
        if (!recoverHome()) return SokoSectionReport("Customers", "Could not reach Soko home", emptyList(), "Could not reach Soko home")
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Customers", "Could not open menu", emptyList(), "Could not open menu")
        if (!actions.clickExactLabel("Customers")) return SokoSectionReport("Customers", "Could not open Customers", emptyList(), "Could not open Customers")
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search", "Add customer").contains(it.trim()) }
        memory.recordAction("soko_customers", null, "Soko Terminal", "Read Customers", "Read customer contacts", "Customer list read.", null, true)
        return SokoSectionReport("Customers", "Customer contacts: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readOrders(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Orders", "Could not open Soko Terminal", emptyList(), "Could not open Soko Terminal")
        if (!recoverHome()) return SokoSectionReport("Orders", "Could not reach Soko home", emptyList(), "Could not reach Soko home")
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Orders", "Could not open menu", emptyList(), "Could not open menu")
        if (!actions.clickExactLabel("Orders")) return SokoSectionReport("Orders", "Could not open Orders", emptyList(), "Could not open Orders")
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
        return SokoSectionReport("Alerts", result.summary, result.summary.split("; "),
            if (result.success) null else result.summary)
    }

    suspend fun readProducts(): SokoSectionReport {
        val result = SokoInventoryModule(config, actions, memory, pin = pin).scan()
        return SokoSectionReport("Products", result.summary,
            result.items.map { it.name }, if (result.success) null else result.summary)
    }

    private suspend fun recoverHome(): Boolean {
        val credential = pin()
        if (credential.isBlank()) return false
        val recovered = actions.recoverSokoHome(credential)
        if (recovered) credentialAudit?.onLoginAccepted()
        else if (actions.snapshot().contains("Staff Login")) {
            credentialAudit?.onLoginRejected(SokoIntelligenceModule.AUTH_REJECTED_CODE)
        }
        return recovered
    }

    suspend fun readRefunds(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Refunds", "Could not open Soko Terminal", emptyList(), "Could not open Soko Terminal")
        if (!recoverHome()) return SokoSectionReport("Refunds", "Could not reach Soko home", emptyList(), "Could not reach Soko home")
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Refunds", "Could not open menu", emptyList(), "Could not open menu")
        if (!actions.clickExactLabel("Refunds")) return SokoSectionReport("Refunds", "Could not open Refunds", emptyList(), "Could not open Refunds")
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Search").contains(it.trim()) }
        memory.recordAction("soko_refunds", null, "Soko Terminal", "Read Refunds", "Read refunds and disputes", "Refunds screen read.", null, true)
        return SokoSectionReport("Refunds", "Refunds: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun readSuppliers(): SokoSectionReport {
        if (!actions.openSokoTerminal()) return SokoSectionReport("Suppliers", "Could not open Soko Terminal", emptyList(), "Could not open Soko Terminal")
        if (!recoverHome()) return SokoSectionReport("Suppliers", "Could not reach Soko home", emptyList(), "Could not reach Soko home")
        if (!actions.clickAndLearn("com.soko24.soko_seller_terminal", "open_more", "More")) return SokoSectionReport("Suppliers", "Could not open menu", emptyList(), "Could not open menu")
        if (!actions.scrollUntil("Suppliers", 5)) return SokoSectionReport("Suppliers", "Could not find Suppliers", emptyList(), "Could not find Suppliers")
        if (!actions.clickExactLabel("Suppliers")) return SokoSectionReport("Suppliers", "Could not open Suppliers", emptyList(), "Could not open Suppliers")
        actions.pause(co.sanaa.agent.core.InteractionKind.APP_LOAD)
        val screen = actions.snapshot()
        val items = screen.visibleText.filter { it.isNotBlank() && !setOf("Back", "More", "Add supplier").contains(it.trim()) }
        memory.recordAction("soko_suppliers", null, "Soko Terminal", "Read Suppliers", "Read supplier list", "Suppliers screen read.", null, true)
        return SokoSectionReport("Suppliers", "Suppliers: ${items.take(8).joinToString("; ")}", items)
    }

    suspend fun fullShopReport(): String {
        return fullShopReportResult().summary
    }

    suspend fun fullShopReportResult(): SokoShopReport {
        val sections = listOf(
            readDashboard(),
            readAlerts(),
            readOrders(),
            readCustomers(),
            readProducts(),
        )
        return SokoShopReport(sections)
    }
}

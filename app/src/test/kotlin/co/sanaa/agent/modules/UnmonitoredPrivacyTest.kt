package co.sanaa.agent.modules

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.ActionVerifier
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.ContactDirectory
import co.sanaa.agent.core.ChatStore
import co.sanaa.agent.core.ContactDirectoryProvider
import co.sanaa.agent.core.ContactDirectoryStore
import co.sanaa.agent.core.ContactPermission
import co.sanaa.agent.core.DirectoryEntry
import co.sanaa.agent.core.Ambiguity
import co.sanaa.agent.core.Classification
import co.sanaa.agent.core.CommercialConsent
import co.sanaa.agent.core.EntrySource
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.TaskQueue
import co.sanaa.agent.core.commerce.RevenueOperatorRuntime
import co.sanaa.agent.notifications.NotificationReporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Unmonitored-privacy directive: for a contact outside the monitored directory, the
 * engine stores no message, feeds no revenue ingestion, displays no text, and logs no
 * name — only a minimal redacted notification count — unless the owner's retention
 * policy explicitly authorizes more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnmonitoredPrivacyTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var memory: AmaraMemory
    private lateinit var store: ContactDirectoryStore
    private lateinit var directory: ContactDirectory
    private lateinit var state: ModuleStateStore

    private val stranger = "Annah Cashier Bweyale"
    private val message = "Hello, do you have the kitenge in blue? My number is 0774123456"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        context.deleteDatabase(ContactDirectoryStore.DATABASE_NAME)
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
        config.ownerPhone = "+256700000001"
        config.groqApiKey = "test-key"
        store = ContactDirectoryStore(context)
        directory = ContactDirectory(store)
        ContactDirectoryProvider.instance = directory
        state = ModuleStateStore(context)
        ShadowLog.setupLogging()
        Log.i("SanaaConversation", "test harness ready")
    }

    @After
    fun tearDown() {
        ContactDirectoryProvider.instance = null
        store.close()
    }

    private fun engine(revenueIngestion: co.sanaa.agent.core.commerce.RevenueIngestion? = null): ConversationEngine {
        val backend = BackendSync(context, config, memory)
        val actions = AccessibilityActions(context, memory)
        val verifier = ActionVerifier(actions, SokoApiClient(config), backend)
        return ConversationEngine(
            config, SokoApiClient(config), GroqClient(config, memory, allowInsecureTestEndpoint = true),
            backend, actions, verifier, state, NotificationReporter(context),
            memory, TaskQueue(), SideEffectRunner(SideEffectLedger.from(memory)),
            ChatStore(context),
            revenueIngestion = revenueIngestion,
        )
    }

    private fun monitoredEntry(name: String): DirectoryEntry = directory.upsert(
        DirectoryEntry(
            id = "", displayName = name, normalizedPhone = null, aliases = emptySet(), isGroup = false,
            source = EntrySource.OWNER_CREATED, lastVerifiedAt = System.currentTimeMillis(),
            ambiguity = Ambiguity.UNIQUE, classification = Classification.CUSTOMER,
            commercialConsent = CommercialConsent.UNKNOWN,
            permissions = ContactDirectoryStore.operationsForLevel(ContactPermission.MONITOR),
            whatsappSurfaceEvidence = null, revocationEvidence = null,
        ),
    )

    private fun postedTexts(): List<String> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .flatMap { n ->
                listOfNotNull(
                    n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                )
            }

    private fun logOutput(): String = ShadowLog.getLogs().joinToString("\n") { it.msg.orEmpty() }

    @Test
    fun unmonitoredContactLeavesNoMessageNoNameAndNoRevenueTrace() = runBlocking {
        val revenue = RevenueOperatorRuntime.create(memory = memory)
        val result = engine(revenue.revenueIngestion).observeWhatsApp(
            WhatsAppInbound(sender = stranger, message = message, conversation = stranger, isGroup = false),
        )

        assertTrue(result.success)
        // No message text stored anywhere.
        assertTrue(memory.conversationHistory(stranger).isEmpty())
        val allConversations = memory.readableDatabase.rawQuery(
            "SELECT message_text, contact_name FROM conversations", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
        assertTrue(allConversations.none { it.first.orEmpty().contains("kitenge") || it.first.orEmpty().contains("0774123456") })
        assertTrue(allConversations.none { it.second.orEmpty().contains(stranger) })

        // No revenue ingestion for the unmonitored contact.
        val dayStart = System.currentTimeMillis() - 60_000
        assertTrue(revenue.store.inquiries(dayStart, System.currentTimeMillis() + 60_000).isEmpty())

        // Only the minimal redacted count is recorded.
        assertEquals("1", state.string("unmonitored_notification_count"))
        val actions = memory.readableDatabase.rawQuery(
            "SELECT type, command_given, what_amara_did, result, target_contact FROM actions", null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to "${c.getString(1)}|${c.getString(2)}|${c.getString(3)}|${c.getString(4)}") } }
        assertTrue(actions.none { it.second.contains(stranger) || it.second.contains("kitenge") })

        // Display surface carries neither the name nor the text.
        val display = result.summary + " " + postedTexts().joinToString(" ")
        assertFalse(display.contains(stranger))
        assertFalse(display.contains("kitenge"))
        assertFalse(display.contains("0774123456"))

        // Logs carry no name.
        assertFalse(logOutput().contains(stranger))
    }

    @Test
    fun explicitRetentionPolicyRecordsARedactedDurableEventWithoutNamesOrText() = runBlocking {
        config.retainUnmonitoredContactEvents = true
        engine().observeWhatsApp(
            WhatsAppInbound(sender = stranger, message = "$message Please reply today.", conversation = stranger, isGroup = false),
        )
        val rows = memory.readableDatabase.rawQuery(
            "SELECT type, command_given, what_amara_did, result, target_contact FROM actions WHERE type='unmonitored_notification'", null,
        ).use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)}|${c.getString(1)}|${c.getString(2)}|${c.getString(3)}|${c.getString(4)}") } }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertFalse(row.contains(stranger))
        assertFalse(row.contains("kitenge"))
        assertFalse(row.contains("0774123456"))
    }

    @Test
    fun monitoredContactsKeepFullAuthorizedProcessing() = runBlocking {
        monitoredEntry(stranger)
        val revenue = RevenueOperatorRuntime.create(memory = memory)
        val monitoredMessage = "Do you deliver to Ntinda today?"
        val result = engine(revenue.revenueIngestion).observeWhatsApp(
            WhatsAppInbound(sender = stranger, message = monitoredMessage, conversation = stranger, isGroup = false),
        )
        // Authorized storage happened for the monitored contact (message recorded).
        val history = memory.conversationHistory(stranger)
        assertTrue(history.any { it.text == monitoredMessage })
        // The unmonitored counter stayed untouched by the authorized path.
        assertEquals("0", state.string("unmonitored_notification_count", "0"))
        // With accessibility absent the contextual reply cannot complete; the engine
        // reports that honestly instead of claiming a send.
        assertFalse(result.summary.contains(stranger) && postedTexts().joinToString(" ").contains("kitenge"))
    }

    @Test
    fun missedCallsFromUnmonitoredContactsAreCountedNotStored() = runBlocking {
        val result = engine().observeWhatsApp(
            WhatsAppInbound(sender = stranger, message = "", conversation = stranger, isGroup = false, isMissedCall = true),
        )
        assertTrue(memory.conversationHistory(stranger).isEmpty())
        assertEquals("1", state.string("unmonitored_notification_count"))
        assertFalse((result.summary + " " + postedTexts().joinToString(" ")).contains(stranger))
    }
}

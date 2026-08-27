package co.sanaa.agent.modules

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.ActionVerifier
import co.sanaa.agent.api.BackendSync
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.Initiator
import co.sanaa.agent.core.ModuleStateStore
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.SideEffectState
import co.sanaa.agent.core.SideEffectTransaction
import co.sanaa.agent.core.work.BroadcastKeys
import co.sanaa.agent.notifications.NotificationReporter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Truthful-outcome contract for the morning broadcast on the JVM wire. Device
 * publication itself is NOT claimed here; every test drives real transaction
 * outcomes (duplicate suppression, rejection, failure) through the ledger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FakeSurfaceShadow::class])
class MorningBroadcastTruthTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var memory: AmaraMemory
    private lateinit var state: ModuleStateStore
    private lateinit var server: MockWebServer
    private lateinit var directoryStore: co.sanaa.agent.core.ContactDirectoryStore

    private val broadcastMessage = "Fresh morning deals in Kampala today"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        context.deleteDatabase(co.sanaa.agent.core.ContactDirectoryStore.DATABASE_NAME)
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
        state = ModuleStateStore(context)
        server = MockWebServer()
        server.start()
        config.groqApiKey = "test-key"
        config.agentToken = "test-agent-token"
        config.deviceId = "test-device"
        config.ownerPhone = "+256700000001"
        // Single authority: broadcast groups resolve from the durable directory (empty here).
        val directory = co.sanaa.agent.core.ContactDirectory(
            co.sanaa.agent.core.ContactDirectoryStore(context).also { directoryStore = it },
        )
        co.sanaa.agent.core.ContactDirectoryProvider.instance = directory
        config.whatsAppGroupsJson = "[]"
        config.groqEndpoint = server.url("/v1/chat/completions").toString()
        config.backendUrl = server.url("/backend").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        co.sanaa.agent.core.ContactDirectoryProvider.instance = null
        directoryStore.close()
        server.shutdown()
    }

    private fun dispatcherFor(listingsJson: String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("/v1/chat/completions") -> groqResponse()
                path.contains("/soko/listings") -> MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[$listingsJson]}""")
                else -> MockResponse().setResponseCode(200).setBody("{}")
            }
        }
    }

    private fun groqResponse(): MockResponse {
        val content = JSONObject.quote(
            """{"broadcast_message":"$broadcastMessage","tiktok_caption":"","featured_product":""}""",
        )
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":$content},"finish_reason":"stop"}]}""")
    }

    private fun listing(id: String) =
        """{"id":"$id","title":"Kitenge Dress $id","description":"Handmade","price_ugx":45000,"view_count":10,"image_path":""}"""

    private fun module(initiators: BroadcastInitiators = BroadcastInitiators()): MorningBroadcastModule {
        val backend = BackendSync(context, config, memory)
        val actions = AccessibilityActions(context, memory)
        val verifier = ActionVerifier(actions, SokoApiClient(config), backend)
        return MorningBroadcastModule(
            config, SokoApiClient(config), GroqClient(config, memory, allowInsecureTestEndpoint = true),
            backend, actions, verifier, state, NotificationReporter(context),
            SideEffectRunner(SideEffectLedger.from(memory)), memory, initiators,
        )
    }

    private fun seedVerifiedTransaction(key: String, capabilityId: String, content: String) {
        val now = System.currentTimeMillis()
        memory.upsertSideEffectTransaction(
            SideEffectTransaction(key, capabilityId, "status", ContentHashing.hash(content), null, SideEffectState.VERIFIED, now, now, "seeded"),
        )
    }

    private fun notificationTitles(): List<String> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .mapNotNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }

    @Test
    fun `duplicate-suppressed status with nothing failed reports verified without claiming a new send`() = runBlocking {
        val dayKey = LocalDate.now().toString()
        seedVerifiedTransaction(BroadcastKeys.status(dayKey, broadcastMessage), CapabilityIds.POST_WHATSAPP_STATUS, broadcastMessage)
        server.dispatcher = dispatcherFor(listing("L1"))

        val result = module(initiators = BroadcastInitiators(status = Initiator.OWNER_CHAT)).run()

        assertTrue(result.success)
        assertEquals("VERIFIED", result.metadata["overall"])
        val summary = result.summary.lowercase()
        assertTrue(summary.contains("already posted"))
        assertFalse(summary.contains("newly sent"))
        // History advanced for non-failed work.
        assertEquals("L1", state.string("last_broadcast_ids"))
        // Success title kept, but only the summary distinguishes suppression from sending.
        assertTrue(notificationTitles().any { it == "Morning done ✅" })
        // No failure records: duplicate suppression counts toward neither success nor failure.
        assertTrue(memory.recentFailures().none { it.stage == "broadcast" })
    }

    @Test
    fun `failed status leg never claims success and never advances history`() = runBlocking {
        server.dispatcher = dispatcherFor(listing("L2"))

        val result = module().run()

        assertFalse(result.success)
        assertEquals("FAILED", result.metadata["overall"])
        val legsAny = result.metadata["legs"] as? List<*>
        assertNotNull(legsAny)
        val statusLeg = legsAny.orEmpty().firstOrNull { (it as Map<*, *>)["leg"] == "status" } as? Map<*, *>
        assertEquals("FAILED", statusLeg?.get("state"))
        // The failed leg carries a concrete honest reason for the failure instead of
        // pretending success; the exact wording tracks the on-device failure mode.
        assertTrue((statusLeg?.get("detail") as? String).orEmpty().isNotBlank())
        assertEquals("", state.string("last_broadcast_ids"))
        assertFalse(notificationTitles().any { it.contains("✅") })
        assertTrue(notificationTitles().any { it == "Morning broadcast needs attention" })
        val failures = memory.recentFailures().filter { it.stage == "broadcast" && it.stepId == "status" }
        assertEquals(1, failures.size)
        assertEquals(CapabilityIds.POST_WHATSAPP_STATUS, failures.first().capability)
        assertEquals("FAILED_PERMANENT", failures.first().disposition)
    }

    @Test
    fun `mixed verified and uncertain legs compute PARTIAL`() {
        val partial = module().overallOf(listOf(
            BroadcastLeg("status", BroadcastLegState.VERIFIED, ""),
            BroadcastLeg("group:A", BroadcastLegState.DUPLICATE_SUPPRESSED, ""),
            BroadcastLeg("tiktok", BroadcastLegState.UNCERTAIN, "proof arrived late"),
        ))
        assertEquals(MorningBroadcastModule.Overall.PARTIAL, partial)
    }

    @Test
    fun `overall classifier covers failed verified and skipped-only runs`() {
        val classifier = module()::overallOf
        assertEquals(MorningBroadcastModule.Overall.FAILED, classifier(listOf(BroadcastLeg("status", BroadcastLegState.FAILED, ""))))
        assertEquals(
            MorningBroadcastModule.Overall.VERIFIED,
            classifier(listOf(BroadcastLeg("status", BroadcastLegState.VERIFIED, ""), BroadcastLeg("group:g", BroadcastLegState.VERIFIED, ""))),
        )
        assertEquals(
            MorningBroadcastModule.Overall.PARTIAL,
            classifier(listOf(BroadcastLeg("status", BroadcastLegState.VERIFIED, ""), BroadcastLeg("group:g", BroadcastLegState.FAILED, ""))),
        )
        assertEquals(MorningBroadcastModule.Overall.FAILED, classifier(emptyList()))
        assertEquals(MorningBroadcastModule.Overall.FAILED, classifier(listOf(BroadcastLeg("tiktok", BroadcastLegState.SKIPPED, ""))))
    }
}

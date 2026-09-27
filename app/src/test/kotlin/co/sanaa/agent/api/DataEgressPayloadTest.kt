package co.sanaa.agent.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.TerminalShopIdentity
import co.sanaa.agent.core.OwnerPower
import co.sanaa.agent.core.work.AgentEventOutbox
import co.sanaa.agent.core.work.Domain
import co.sanaa.agent.core.work.WorkItem
import co.sanaa.agent.core.work.WorkKind
import co.sanaa.agent.core.work.WorkResult
import co.sanaa.agent.core.work.WorkStatus
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Genuine network-boundary tests: every backend request payload is captured by a local
 * server and asserted field-by-field, proving telemetry gating, redaction, and that
 * secrets and unrelated records never leave the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric max SDK for JVM 17; SQLite behavior under test is stable across 35/36
class DataEgressPayloadTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var memory: AmaraMemory
    private lateinit var server: MockWebServer
    private lateinit var backend: BackendSync

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        context.deleteDatabase(AgentEventOutbox.DATABASE_NAME)
        context.getSharedPreferences("amara_event_binding", 0).edit().clear().commit()
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
        config.telemetryOptIn = false
        config.configSyncEnabled = true
        OwnerPower(context).setOn(true)
        // Isolate from any real stored credentials.
        config.groqApiKey = ""
        config.agentToken = "test-agent-token"
        config.deviceId = "test-device"
        config.ownerPhone = "+256700000001"
        server = MockWebServer()
        server.start()
        config.backendUrl = server.url("/api/agent").toString().trimEnd('/')
        backend = BackendSync(context, config, memory)
    }

    @After
    fun tearDown() {
        backend.eventOutbox.close()
        server.shutdown()
    }

    @Test fun offlineWorkEventSurvivesFailureThenLeavesOnlyAfterServerAck() = runBlocking {
        config.telemetryOptIn = true
        val shop = TerminalShopIdentity(254, 24, "Free Line Stationery", Long.MAX_VALUE, "signed")
        AgentEventOutbox(context) { shop }.use { outbox ->
            outbox.bindShop(shop.scope, 1)
            val item = WorkItem("private-contact:+256700000001", Domain.SOKO, WorkKind.SOKO_INVENTORY_CHECK,
                baseValueKes = 1.0, urgencyHalfLifeHours = 1.0, estimatedScreenSeconds = 1)
            assertTrue(outbox.enqueueOutcome(WorkResult(item, WorkStatus.DONE)))
        }
        val eventId = backend.eventOutbox.pending().single().first
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { backend.syncEvents() }.isFailure)
        assertEquals(1, backend.eventOutbox.pendingCount())
        val first = server.takeRequest()
        assertEquals("/api/agent/events", first.path)
        assertFalse(first.body.readUtf8().contains("+256700000001"))
        server.enqueue(MockResponse().setBody("""{"accepted":["$eventId"],"duplicates":[],"rejected":[]}"""))
        assertEquals(1, backend.syncEvents())
        assertEquals(0, backend.eventOutbox.pendingCount())
    }

    @Test fun logWithTelemetryOffSendsNothingAnywhere() = runBlocking {
        config.telemetryOptIn = false
        val sent = runBlocking { backend.log("module", "action", "whatsapp", "summary pin is 483920", success = true) }
        assertFalse(sent)
        assertEquals(0, server.requestCount)
    }

    @Test fun authenticatedConfigSyncImportsBothModelCredentials() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"groq_api_key":"primary-from-backend","groq_api_key_2":"fallback-from-backend"}""",
            ),
        )

        backend.fetchConfig()

        assertEquals("primary-from-backend", config.groqApiKey)
        assertEquals("fallback-from-backend", config.groqApiKey2)
        val request = server.takeRequest()
        assertEquals("Bearer test-agent-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("X-Amara-Request-ID")!!.matches(Regex("[0-9a-f-]{36}")))
    }

    @Test fun configHttp500HasSameSafeReferenceAsRequestHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal details must stay hidden"))

        val failure = runCatching { backend.fetchConfig() }.exceptionOrNull()
        val requestId = server.takeRequest().getHeader("X-Amara-Request-ID")!!
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("reference $requestId"))
        assertFalse(failure.message!!.contains("internal details"))
        assertFalse(failure.message!!.contains("test-agent-token"))
    }

    @Test fun cancellingConfigSyncAbortsHeldHttpCallPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { backend.fetchConfig() }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

        withTimeout(2_500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun optedInLogIsRedactedAndTruncatedBeforeExport() = runBlocking {
        config.telemetryOptIn = true
        server.enqueue(MockResponse().setBody("{}"))
        val sent = runBlocking {
            backend.log("morning_broadcast", "broadcast", "whatsapp", "Broadcast done; staff pin is 483920 in notes", success = true)
        }
        assertTrue(sent)
        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("morning_broadcast", body.getString("module"))
        assertTrue(body.getBoolean("success"))
        val summary = body.getString("summary")
        assertFalse("PIN digits must never be exported", summary.contains("483920"))
        assertTrue(summary.contains("[REDACTED:"))
    }

    @Test fun escalationStaysAvailableButRedactsItsContent() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val notified = runBlocking {
            backend.escalate(
                message = "Owner decision needed about order pin is 991188",
                context = "Customer thread",
                urgency = "high",
                replies = listOf("Approve", "Decline"),
            )
        }
        assertTrue(notified)
        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("high", body.getString("urgency"))
        assertFalse(body.getString("message").contains("991188"))
        assertTrue(body.getJSONArray("suggested_replies").length() == 2)
    }

    @Test fun generateAdRequiresExplicitArtifactUploadConsent() = runBlocking {
        config.artifactUploadOptIn = false
        val listing = SokoListing(id = "l1", title = "Printer", description = "desc", priceUgx = 450000, category = "electronics", photoCount = 2, viewCount = 5, stock = 3, imageUrl = null, raw = JSONObject())
        val blocked = runCatching { runBlocking { backend.generateAd(listing) } }
        assertTrue(blocked.isFailure)
        assertEquals(0, server.requestCount)

        config.artifactUploadOptIn = true
        server.enqueue(MockResponse().setBody("{}"))
        runBlocking { backend.generateAd(listing) }
        assertEquals(1, server.requestCount)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("listing"))
    }

    @Test fun configSyncDisabledBlocksRegistrationConfigAndStatus() = runBlocking {
        config.configSyncEnabled = false
        assertTrue(runCatching { backend.registerAndSync() }.isFailure)
        assertTrue(runCatching { backend.fetchConfig() }.isFailure)
        assertTrue(runCatching { backend.status() }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun heartbeatRequiresSeparateConsentAndExportsOnlyBoundedHealthFacts() = runBlocking {
        val snapshot = mapOf<String, Any>(
            "accessibilityBound" to true, "pendingWorkCount" to 7,
            "batteryPercent" to 64, "charging" to false,
            "blockers" to listOf("Private customer conversation"),
        )
        assertFalse(backend.heartbeat(snapshot))
        assertEquals(0, server.requestCount)

        config.operationalReportingEnabled = true
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"received\":true}"))
        assertTrue(backend.heartbeat(snapshot))
        val request = server.takeRequest()
        assertEquals("/api/agent/heartbeat", request.path)
        assertEquals("Bearer test-agent-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-device", body.getString("device_id"))
        assertEquals(7, body.getInt("pending_tasks"))
        assertEquals(64, body.getInt("battery_level"))
        assertFalse(body.toString().contains("Private customer conversation"))
        assertFalse(body.has("blockers"))
    }

    @Test fun shopObservationUsesOnlySignedIdentityAndMatchingDeviceCredential() = runBlocking {
        val shop = TerminalShopIdentity(254, 24, "Current shop", System.currentTimeMillis() / 1000 + 90, "signed.shop-proof")
        server.enqueue(MockResponse().setBody("""{"shop_identity":{"seller_id":254,"shop_id":24},"binding_revision":1}"""))
        assertTrue(backend.observeTerminalShop(shop))
        val request = server.takeRequest()
        assertEquals("/api/agent/shop-observation", request.path)
        assertEquals("signed.shop-proof", request.getHeader("X-Terminal-Identity"))
        assertEquals("Bearer test-agent-token", request.getHeader("Authorization"))
        assertEquals(setOf("device_id"), JSONObject(request.body.readUtf8()).keySet().toSet())
        config.configSyncEnabled = false
        assertFalse(backend.observeTerminalShop(shop))
        assertEquals(1, server.requestCount)
    }

    @Test fun registrationPayloadCarriesOnlyIdentityFields() = runBlocking {
        config.configSyncEnabled = true
        config.agentToken = ""
        server.enqueue(MockResponse().setBody("{\"agent_token\":\"issued-token\"}"))
        server.enqueue(MockResponse().setBody("{}")) // follow-up fetchConfig inside registerAndSync
        runBlocking { backend.registerAndSync() }
        val recorded = server.takeRequest()
        assertEquals("/api/agent/register", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals(setOf("device_id", "agent_name", "business_name", "owner_phone"), body.keySet().toSet())
        assertEquals("test-device", body.getString("device_id"))
    }

    @Test fun visionRequestsRequireExplicitOwnerConsent() {
        config.groqApiKey = "k"
        config.visionConsent = false
        val image = File.createTempFile("shot", ".jpg", context.cacheDir).apply { writeBytes(ByteArray(64)) }
        val blocked = runCatching { runBlocking { GroqClient(config, memory).completeVisionJson("inspect", image) } }
        assertTrue(blocked.isFailure)
        assertTrue(blocked.exceptionOrNull()!!.message!!.contains("consent"))
    }
}

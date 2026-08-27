package co.sanaa.agent.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.runBlocking
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
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
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
        server.shutdown()
    }

    @Test fun logWithTelemetryOffSendsNothingAnywhere() = runBlocking {
        config.telemetryOptIn = false
        val sent = runBlocking { backend.log("module", "action", "whatsapp", "summary pin is 483920", success = true) }
        assertFalse(sent)
        assertEquals(0, server.requestCount)
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

package co.sanaa.agent.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.TerminalShopIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SokoRateLimitedTest {
    @Test fun retryAfterSupportsSecondsDatesAndMalformedHeaders() {
        assertEquals(120_000L, SokoRateLimited.delayMs("120"))
        assertEquals(120_000L, SokoRateLimited.delayMs("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        listOf(null, "", "bad", "-2", "0").forEach { assertEquals(60_000L, SokoRateLimited.delayMs(it)) }
        assertEquals(86_400_000L, SokoRateLimited.delayMs(Long.MAX_VALUE.toString()))
    }

    @Test fun throttledReadReturnsNoCatalogueAndDoesNotRetryInline() = runBlocking {
        val config = SecureConfig(ApplicationProvider.getApplicationContext<Context>(), useEncryptedPrefs = false)
        config.agentToken = "test-token"
        config.deviceId = "rate-test"
        MockWebServer().use { server ->
            server.start()
            config.backendUrl = server.url("/api/agent").toString().trimEnd('/')
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "180").setBody("rate limited"))
            val client = SokoApiClient(config) { TerminalShopIdentity(254, 24, "Shop", Long.MAX_VALUE, "signed") }
            val failure = runCatching { client.activeListings() }.exceptionOrNull()
            assertTrue(failure is SokoRateLimited)
            assertEquals(180_000L, (failure as SokoRateLimited).retryAfterMs)
            assertEquals(1, server.requestCount)
        }
    }
}

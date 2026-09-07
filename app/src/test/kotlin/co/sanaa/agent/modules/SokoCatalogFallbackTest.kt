package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.api.SokoApiClient
import co.sanaa.agent.core.ChatStore
import co.sanaa.agent.core.SecureConfig
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
class SokoCatalogFallbackTest {
    @Test fun unavailableTerminalUsesAuthenticatedProductsAndServicesWithoutPin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SecureConfig(context, useEncryptedPrefs = false)
        MockWebServer().use { server ->
            server.start()
            config.agentToken = "test-token"
            config.deviceId = "test-device"
            config.backendUrl = server.url("/agent").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"1","title":"Test chair","price_ugx":123}]}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"2","title":"Test design","base_price":456,"currency":"UGX"}]}"""))
            ChatStore(context).use { store ->
                val result = SokoCatalogModule(AccessibilityActions(context), store, backend = SokoApiClient(config)).syncAll()
                assertTrue(result.complete)
                assertEquals(1, result.productsSynced)
                assertEquals(1, result.servicesSynced)
                assertTrue(store.getProducts().any { it.contains("Test chair") })
                assertTrue(store.getServices().any { it.contains("Test design") })
            }
            for (resource in listOf("listings", "services")) {
                val request = server.takeRequest()
                assertEquals("/agent/soko/$resource?device_id=test-device", request.path)
                assertEquals("Bearer test-token", request.getHeader("Authorization"))
                assertEquals("GET", request.method)
            }
        }
    }
}

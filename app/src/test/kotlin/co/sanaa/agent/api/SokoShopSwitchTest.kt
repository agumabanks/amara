package co.sanaa.agent.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.TerminalShopIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SokoShopSwitchTest {
    @Test fun combinedCatalogueRejectsShopSwitchBetweenResources() = runBlocking {
        val config = SecureConfig(ApplicationProvider.getApplicationContext<Context>(), useEncryptedPrefs = false)
        config.agentToken = "test-token"
        config.deviceId = "catalogue-device"
        MockWebServer().use { server ->
            server.start()
            config.backendUrl = server.url("/api/agent").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"shop_identity":{"seller_id":254,"shop_id":24},"data":[{"id":"1","title":"Old shop product"}]}"""))
            server.enqueue(MockResponse().setBody("""{"shop_identity":{"seller_id":254,"shop_id":41},"data":[{"id":"2","title":"New shop service","currency":"UGX"}]}"""))
            var reads = 0
            val client = SokoApiClient(config) {
                // Initial combined scope, product start, pre-request and post-response
                // all belong to shop 24; the next resource starts in shop 41.
                val shop = if (++reads <= 4) 24L else 41L
                TerminalShopIdentity(254, shop, "Shop", Long.MAX_VALUE, "signed-$shop")
            }
            val result = runCatching { client.promotableOfferings() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("combined catalogue discarded") == true)
        }
    }
    @Test fun readsLaterCataloguePagesAndRejectsRepeatingCursor() = runBlocking {
        val config = SecureConfig(ApplicationProvider.getApplicationContext<Context>(), useEncryptedPrefs = false)
        config.agentToken = "test-token"
        config.deviceId = "catalogue-device"
        MockWebServer().use { server ->
            server.start()
            config.backendUrl = server.url("/api/agent").toString().trimEnd('/')
            fun page(id: Int, next: String) = MockResponse().setBody("""{"shop_identity":{"seller_id":254,"shop_id":24},"data":[{"id":"$id","title":"Item $id"}],"next_after_id":$next}""")
            server.enqueue(page(100, "100"))
            server.enqueue(page(101, "null"))
            var proofVersion = 0
            val client = SokoApiClient(config) { TerminalShopIdentity(254,24,"Shop",Long.MAX_VALUE,"signed-${++proofVersion}") }
            assertEquals(listOf("100", "101"), client.activeListings().map { it.id })
            assertEquals("signed-2", server.takeRequest().getHeader("X-Terminal-Identity"))
            val secondPage = server.takeRequest()
            assertTrue(secondPage.path!!.endsWith("after_id=100"))
            assertEquals("signed-4", secondPage.getHeader("X-Terminal-Identity"))
            server.enqueue(page(100, "100"))
            server.enqueue(page(100, "100"))
            assertTrue(runCatching { client.activeListings() }.isFailure)
        }
    }

    @Test fun catalogueFromPreviousShopIsDiscardedAndNextReadUsesNewSignedShop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SecureConfig(context, useEncryptedPrefs = false)
        config.agentToken = "shop-switch-token"
        config.deviceId = "shop-switch-device"
        MockWebServer().use { server ->
            server.start()
            config.backendUrl = server.url("/api/agent").toString().trimEnd('/')
            var currentShop = 24L
            var requests = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests++
                    assertEquals("/api/agent/soko/listings?device_id=shop-switch-device&after_id=0", request.path)
                    assertEquals("Bearer shop-switch-token", request.getHeader("Authorization"))
                    val shop = if (requests == 1) 24 else 41
                    if (requests == 1) currentShop = 41L // owner switched while old response was in flight
                    return MockResponse().setBody("""{"shop_identity":{"seller_id":254,"shop_id":$shop},"data":[{"id":"product-$shop","title":"Product $shop","shop_name":"Shop $shop"}]}""")
                }
            }
            val client = SokoApiClient(config) {
                TerminalShopIdentity(254, currentShop, "Shop $currentShop", Long.MAX_VALUE, "signed-$currentShop")
            }
            val old = runCatching { client.activeListings() }
            assertTrue(old.isFailure)
            assertTrue(old.exceptionOrNull()?.message?.contains("shop changed", true) == true)
            val current = client.activeListings().single()
            assertEquals("product-41", current.id)
            assertEquals("254:41", current.raw.getString("shop_scope"))
        }
    }
}

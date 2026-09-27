package co.sanaa.agent.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableExchangeTest {
    @Test fun deadlineCancelsDelayedResponseBodyWithoutWaitingForReadTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(2,TimeUnit.SECONDS))
            val call=OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val start=System.nanoTime()
            assertNull(withTimeoutOrNull(150) { cancellableExchange(call) })
            assertTrue(call.isCanceled())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500)
        }
    }
    @Test fun completeExchangePreservesStatusHeadersAndBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After","2").setBody("limited"))
            val (response, body)=cancellableExchange(OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()))
            assertEquals(429,response.code);assertEquals("2",response.header("Retry-After"));assertEquals("limited",body)
        }
    }
}

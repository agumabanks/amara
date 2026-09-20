package co.sanaa.agent.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ShopSessionRecoveryTest {
    @Test fun validSessionNeverChangesForeground() = runBlocking {
        assertEquals("shop",ShopSessionRecovery(read={"shop"},reopen={error("Must not reopen")},settle={}).ensure())
    }
    @Test fun coldPublisherRecoversWithBoundedReads() = runBlocking {
        var reads=0;var opens=0
        val recovered=ShopSessionRecovery(read={ if(++reads<3) error("Not ready") else "fresh" },reopen={opens++;true},settle={}).ensure()
        assertEquals("fresh",recovered);assertEquals(1,opens);assertEquals(3,reads)
    }
    @Test fun missingSessionStopsAfterThreePostOpenChecks() = runBlocking {
        var reads=0
        try { ShopSessionRecovery<String>(read={reads++;error("Signed out")},reopen={true},settle={}).ensure();fail() }
        catch(e:IllegalStateException) { assertTrue(e.message!!.contains("No business action attempted")) }
        assertEquals(4,reads)
    }
    @Test fun cancellationNeverReopensAnApp() = runBlocking {
        try { ShopSessionRecovery<String>(read={throw CancellationException()},reopen={error("Must not open")},settle={}).ensure();fail() }
        catch(_:CancellationException) { }
    }
}

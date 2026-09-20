package co.sanaa.agent.api

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class BackendDnsTest {
    private fun resolver(block: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String) = block(hostname)
    }
    private val address=InetAddress.getByAddress(byteArrayOf(1,2,3,4))
    @Test fun workingSystemResolverDoesNotUseFallback() {
        val dns=BackendDns({"backend.example"},resolver { listOf(address) },resolver { error("Unneeded fallback") })
        assertEquals(listOf(address),dns.lookup("backend.example"))
    }
    @Test fun onlyFailedConfiguredHostUsesFallback() {
        var queried=""
        val dns=BackendDns({"backend.example"},resolver { throw UnknownHostException(it) },resolver { queried=it;listOf(address) })
        assertEquals(listOf(address),dns.lookup("backend.example"))
        assertEquals("backend.example",queried)
        queried=""
        assertThrows(UnknownHostException::class.java) { dns.lookup("internal.local") }
        assertEquals("",queried)
    }
    @Test fun failedFallbackCannotProduceInventedAddresses() {
        val dns=BackendDns({"backend.example"},resolver { throw UnknownHostException(it) },resolver { throw UnknownHostException(it) })
        val failure=assertThrows(UnknownHostException::class.java) { dns.lookup("backend.example") }
        assertEquals(1,failure.suppressed.size)
    }
}

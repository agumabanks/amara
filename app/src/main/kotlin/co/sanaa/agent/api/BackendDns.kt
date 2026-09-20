package co.sanaa.agent.api

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Recover a stale network resolver for the configured public backend only. */
class BackendDns(
    private val allowedHost: () -> String?,
    private val system: Dns = Dns.SYSTEM,
    private val fallback: Dns = httpsResolver,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        system.lookup(hostname)
    } catch (original: UnknownHostException) {
        if (!hostname.equals(allowedHost(), true)) throw original
        try { fallback.lookup(hostname).ifEmpty { throw original } }
        catch (failure: UnknownHostException) {
            if (failure !== original) original.addSuppressed(failure)
            throw original
        }
    }
    companion object {
        private val httpsResolver: Dns by lazy {
            DnsOverHttps.Builder().client(OkHttpClient.Builder()
                .connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
                .callTimeout(8,TimeUnit.SECONDS).build())
                .url("https://dns.google/dns-query".toHttpUrl()).build()
        }
        fun forUrl(url: () -> String): BackendDns = BackendDns({
            runCatching { java.net.URI(url()).host }.getOrNull()
        })
    }
}

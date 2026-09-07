package co.sanaa.agent.core.knowledge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ConnectivityMonitor — Tracks internet state so Amara doesn't waste time.
 * 
 * Flows:
 * - ONLINE: Full internet access (WiFi or mobile data with working connection)
 * - LIMITED: Connected but no actual internet (captive portal, poor signal)
 * - OFFLINE: No connection at all
 * 
 * Amara checks this before any network operation. If offline, she skips
 * network tasks and focuses on local work (inventory, drafting, learning).
 */
class ConnectivityMonitor(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    enum class ConnectivityState { ONLINE, LIMITED, OFFLINE }

    data class NetworkState(
        val state: ConnectivityState,
        val transportType: String,  // WIFI, CELLULAR, NONE
        val validated: Boolean,     // Actually has internet, not just connected
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Get current network state (synchronous check).
     */
    fun getCurrentState(): NetworkState {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (network == null || capabilities == null) {
            return NetworkState(ConnectivityState.OFFLINE, "NONE", false)
        }

        val transportType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }

        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val state = when {
            !validated -> ConnectivityState.LIMITED
            else -> ConnectivityState.ONLINE
        }

        return NetworkState(state, transportType, validated)
    }

    /**
     * Check if we have actual internet access (not just a connection).
     * Does a real socket check to confirm.
     */
    fun hasInternetAccess(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get connectivity state as Flow for reactive monitoring.
     */
    fun connectivityFlow(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentState())
            }

            override fun onLost(network: Network) {
                trySend(getCurrentState())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(getCurrentState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(getCurrentState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Get a summary string for prompts.
     */
    fun getSummary(): String {
        val state = getCurrentState()
        return when (state.state) {
            ConnectivityState.ONLINE -> "Internet: ONLINE (${state.transportType})"
            ConnectivityState.LIMITED -> "Internet: LIMITED (connected but no access)"
            ConnectivityState.OFFLINE -> "Internet: OFFLINE"
        }
    }

    /**
     * Get simple boolean for quick checks.
     */
    fun isOnline(): Boolean = getCurrentState().state == ConnectivityState.ONLINE

    /**
     * Get connectivity info for work queue filtering.
     */
    fun getConnectivityInfo(): ConnectivityInfo {
        val state = getCurrentState()
        return ConnectivityInfo(
            isOnline = state.state == ConnectivityState.ONLINE,
            transportType = state.transportType,
            validated = state.validated
        )
    }

    data class ConnectivityInfo(
        val isOnline: Boolean,
        val transportType: String,
        val validated: Boolean
    )
}

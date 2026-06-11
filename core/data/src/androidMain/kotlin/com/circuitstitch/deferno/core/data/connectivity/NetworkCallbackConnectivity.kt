package com.circuitstitch.deferno.core.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [Connectivity] (#158): mirrors the OS **default network** into [online] via
 * [ConnectivityManager.registerDefaultNetworkCallback], seeded from the active network so the value
 * is meaningful before the first callback lands. AppScope, registered for the process lifetime —
 * never unregistered, matching the singleton's own lifetime. Needs `ACCESS_NETWORK_STATE`.
 *
 * "Online" is an INTERNET-capable default network — deliberately not VALIDATED, which lags
 * connection by a captive-portal probe; a false positive just lets the request's own transport
 * failure be the signal (the seam's fail-open posture).
 */
class NetworkCallbackConnectivity(context: Context) : Connectivity {

    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val state = MutableStateFlow(currentlyOnline())

    /** The network the OS most recently made default; callbacks arrive serially on one handler. */
    private var defaultNetwork: Network? = null

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
                state.value = true
            }

            override fun onLost(network: Network) {
                // On a wifi→cellular handover onAvailable(new) can precede onLost(old): only the
                // CURRENT default going away means offline, not the superseded network's teardown.
                if (network == defaultNetwork) {
                    defaultNetwork = null
                    state.value = false
                }
            }
        })
    }

    override val online: StateFlow<Boolean> = state.asStateFlow()

    private fun currentlyOnline(): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

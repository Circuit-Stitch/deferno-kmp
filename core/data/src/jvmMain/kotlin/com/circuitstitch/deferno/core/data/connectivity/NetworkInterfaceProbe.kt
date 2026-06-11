package com.circuitstitch.deferno.core.data.connectivity

import java.net.NetworkInterface

/**
 * The desktop's best-effort reachability probe (#158): any non-loopback interface that is up. Catches
 * the flight-mode / cable-pulled / wifi-off shapes of offline; "router up, internet down" still looks
 * online, which the seam's fail-open posture tolerates (the request's transport failure is the real
 * signal). Wrapped by [PollingConnectivity], whose runCatching fail-open also absorbs the
 * `SocketException` the interface enumeration can throw.
 */
internal fun anyNetworkInterfaceUp(): Boolean {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
    for (nic in interfaces) {
        if (nic.isUp && !nic.isLoopback) return true
    }
    return false
}

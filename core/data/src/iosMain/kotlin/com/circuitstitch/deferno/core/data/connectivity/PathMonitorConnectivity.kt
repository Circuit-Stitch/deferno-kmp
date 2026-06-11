package com.circuitstitch.deferno.core.data.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * iOS [Connectivity] (#158): mirrors `NWPathMonitor`'s path status into [online]. AppScope, started
 * for the process lifetime (never cancelled, matching the singleton's own lifetime); updates arrive
 * on a private serial queue — `MutableStateFlow` is thread-safe, so no hop is needed.
 *
 * Optimistically online until the monitor's first path update lands (it fires immediately on start),
 * per the seam's fail-open posture: a false positive just lets the request's own transport failure
 * be the signal.
 */
class PathMonitorConnectivity : Connectivity {

    private val state = MutableStateFlow(true)

    /** Retained for the process lifetime — releasing the monitor would stop the updates. */
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("deferno.connectivity", null))
        nw_path_monitor_start(monitor)
    }

    override val online: StateFlow<Boolean> = state.asStateFlow()
}

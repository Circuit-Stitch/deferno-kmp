package com.circuitstitch.deferno.desktop.update

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the desktop self-update [UpdateState] over an [UpdateBackend] (#103, ADR-0021): exposes a
 * [state] StateFlow the Help menu + menu-bar badge observe, a [checkForUpdates] action (the "Check for
 * updates" menu item and the one-shot startup probe), and [installUpdate] (apply an available update,
 * handing off to the OS updater). Constructed once in `main()` with [ConveyorUpdateBackend].
 *
 * State machine: starts [UpdateState.Idle] (or [UpdateState.Unsupported] off a Win/Mac package).
 * [checkForUpdates] → [UpdateState.Checking] → [UpdateState.Available] / [UpdateState.UpToDate] /
 * [UpdateState.Failed]; [installUpdate] from `Available` → [UpdateState.Installing] (then the OS
 * restarts the app). Checks are skipped when unsupported or one is already in flight.
 *
 * @param scope the long-lived UI scope checks launch on (the app's process scope in `main()`).
 * @param ioContext where the backend's blocking HTTP check runs — `Dispatchers.IO` in production, a
 *   controlled test dispatcher under test.
 */
class UpdateManager(
    private val backend: UpdateBackend,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun initialState(): UpdateState {
        val version = backend.currentVersion
        return when (backend.availability()) {
            UpdateAvailability.AVAILABLE -> UpdateState.Idle(version)
            UpdateAvailability.NOT_PACKAGED ->
                UpdateState.Unsupported(version, UnsupportedReason.NOT_PACKAGED)
            UpdateAvailability.PACKAGE_MANAGER ->
                UpdateState.Unsupported(version, UnsupportedReason.PACKAGE_MANAGER)
            UpdateAvailability.OTHER ->
                UpdateState.Unsupported(version, UnsupportedReason.OTHER)
        }
    }

    /**
     * Probe the update site for a newer version. No-op when self-update is unsupported here or a check /
     * install is already in flight (so repeated clicks + the startup probe don't stack). Safe to call
     * from the UI thread — the blocking fetch runs on [ioContext].
     */
    fun checkForUpdates() {
        if (backend.availability() != UpdateAvailability.AVAILABLE) return
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Installing -> return
            else -> Unit
        }
        val version = backend.currentVersion
        _state.value = UpdateState.Checking(version)
        scope.launch {
            _state.value = try {
                when (val result = withContext(ioContext) { backend.checkForUpdate() }) {
                    is CheckResult.Available -> UpdateState.Available(version, result.latestVersion)
                    is CheckResult.UpToDate -> UpdateState.UpToDate(version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(version, e.message ?: "Update check failed")
            }
        }
    }

    /**
     * Apply the available update — hands off to Conveyor's OS-native updater, which closes the app,
     * installs, and relaunches. Only valid from [UpdateState.Available]; ignored otherwise.
     */
    fun installUpdate() {
        val current = _state.value
        if (current is UpdateState.Available) {
            _state.value = UpdateState.Installing(current.currentVersion, current.latestVersion)
            backend.triggerUpdate()
        }
    }
}

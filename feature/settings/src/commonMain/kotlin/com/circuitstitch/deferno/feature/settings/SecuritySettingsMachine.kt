package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.security.SecurityResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The **Security & 2FA** step machine (#72 follow-through) — the webui SecurityPane's behavior,
 * Compose-free: [DefaultSettingsComponent] delegates the category's [SettingsComponent] intents here
 * 1:1 and republishes [state] as [SettingsComponent.security]. Split out of the component because
 * this is the first category whose logic is a multi-step modal flow rather than a field write —
 * the catalog component stays a catalog, and the machine is instantiable on its own.
 *
 * The step machine: a gated mutation the server refuses with the step-up 403 opens
 * [SecuritySettings.Flow.StepUp] carrying the *typed* [PendingSecurityAction], and a successful
 * password re-verification resumes that exact action — including re-verifying the same code against
 * the same secret. At most one mutation is in flight ([SecuritySettings.busy] — intents are no-ops
 * while set).
 */
internal class SecuritySettingsMachine(
    private val scope: CoroutineScope,
    private val repository: SecurityRepository,
    // The Active Account's own token id (`Account.tokenId`) — marks "this device" in the
    // connected-devices list and withholds its revoke (sign-out owns that path).
    private val activeTokenId: String?,
) {

    // Seeded Loading; the drill-in triggers [refresh] — never cached across visits (the factor
    // list is server truth the sign-in challenge shares).
    private val _state = MutableStateFlow(SecuritySettings())
    val state: StateFlow<SecuritySettings> = _state.asStateFlow()

    // The current drill-in's fetches, cancelled by the next [refresh] — so a slow, stale read from
    // a previous visit can never land after (and clobber) a fresh one.
    private var refreshJob: Job? = null

    /** The drill-in fetch (and the Unavailable retry): re-read the 2FA summary + connected devices. */
    fun refresh() {
        // Cancel any in-flight fetches, then reset to Loading and drop any stale flow — a
        // re-entered category never resumes an old step.
        refreshJob?.cancel()
        _state.value = SecuritySettings()
        refreshJob = scope.launch {
            launch {
                val status = repository.status()
                _state.update {
                    it.copy(
                        overview = when (status) {
                            is SecurityResult.Success ->
                                SecuritySettings.Overview.Ready(status.value.totpEnabled, status.value.emailBackup)
                            else -> SecuritySettings.Overview.Unavailable
                        },
                    )
                }
            }
            launch {
                val devices = repository.connectedDevices()
                _state.update {
                    it.copy(
                        devices = when (devices) {
                            is SecurityResult.Success ->
                                SecuritySettings.Devices.Ready(devices.value, activeTokenId)
                            else -> SecuritySettings.Devices.Unavailable
                        },
                    )
                }
            }
        }
    }

    fun enrollTotp() = dispatchGated(PendingSecurityAction.EnrollTotp)

    fun disableMfa() = dispatchGated(PendingSecurityAction.DisableMfa)

    fun addEmailBackup() = dispatchGated(PendingSecurityAction.AddEmailBackup)

    fun removeEmailBackup() = dispatchGated(PendingSecurityAction.RemoveEmailBackup)

    fun submitEnrollCode(code: String) {
        val step = _state.value.flow as? SecuritySettings.Flow.EnterCode ?: return
        dispatchGated(PendingSecurityAction.VerifyCode(step.enrollment, code))
    }

    fun submitStepUp(password: String) {
        val step = _state.value.flow as? SecuritySettings.Flow.StepUp ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, lastActionFailed = false, flow = step.copy(wrongPassword = false)) }
        scope.launch {
            when (repository.stepUp(password)) {
                // Freshness stamped — resume the exact pending action (same code, same secret for a
                // gated verify; the whole point of the typed [PendingSecurityAction]).
                is SecurityResult.Success -> applyOutcome(step.pending, runAction(step.pending))
                // Step-up's Rejected is "wrong password" (or budget exhausted — deliberately
                // indistinguishable server-side); stay on the sheet for another attempt.
                SecurityResult.Rejected -> _state.update { it.copy(flow = step.copy(wrongPassword = true)) }
                else -> _state.update { it.copy(lastActionFailed = true) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Dismiss the step-up sheet — abandons the pending action (nothing was mutated server-side). */
    fun dismissStepUp() {
        _state.update { if (it.flow is SecuritySettings.Flow.StepUp) it.copy(flow = null) else it }
    }

    /** Dismiss the code-entry step — abandons enrollment (a later enroll restarts with a fresh secret). */
    fun dismissEnroll() {
        _state.update { if (it.flow is SecuritySettings.Flow.EnterCode) it.copy(flow = null) else it }
    }

    fun acknowledgeRecoveryCodes() {
        // The one exit from the recovery-codes step (the View offers no other) — the summary was
        // already flipped to enrolled when the verify succeeded.
        _state.update { if (it.flow is SecuritySettings.Flow.RecoveryCodes) it.copy(flow = null) else it }
    }

    fun revokeDevice(tokenId: String) {
        if (tokenId == activeTokenId) return // this device — sign-out owns that path (never revoke-in-place)
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, lastActionFailed = false) }
        scope.launch {
            when (repository.revokeDevice(tokenId)) {
                is SecurityResult.Success -> _state.update { s ->
                    val devices = s.devices
                    if (devices is SecuritySettings.Devices.Ready) {
                        s.copy(devices = devices.copy(devices = devices.devices.filterNot { it.id == tokenId }))
                    } else {
                        s
                    }
                }
                else -> _state.update { it.copy(lastActionFailed = true) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** One gated mutation at a time: dispatch [action], routing its outcome through [applyOutcome]. */
    private fun dispatchGated(action: PendingSecurityAction) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, lastActionFailed = false) }
        scope.launch {
            applyOutcome(action, runAction(action))
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Issue [action]'s server call, applying its success step-transition inline (so a step-up
     * *resume* transitions identically to a first attempt). Returns the raw result for [applyOutcome].
     */
    private suspend fun runAction(action: PendingSecurityAction): SecurityResult<*> = when (action) {
        PendingSecurityAction.EnrollTotp -> repository.enrollStart().also { r ->
            if (r is SecurityResult.Success) {
                _state.update { it.copy(flow = SecuritySettings.Flow.EnterCode(r.value)) }
            }
        }
        is PendingSecurityAction.VerifyCode -> repository.enrollVerify(action.code).also { r ->
            if (r is SecurityResult.Success) {
                // Enrolled: show the one-shot recovery codes and flip the summary in place (the next
                // drill-in re-reads server truth anyway).
                _state.update {
                    it.copy(
                        flow = SecuritySettings.Flow.RecoveryCodes(r.value),
                        overview = it.overview.withTotp(enabled = true),
                    )
                }
            }
        }
        PendingSecurityAction.DisableMfa -> repository.disableMfa().also { r ->
            if (r is SecurityResult.Success) {
                _state.update {
                    it.copy(flow = null, overview = SecuritySettings.Overview.Ready(totpEnabled = false, emailBackup = false))
                }
            }
        }
        PendingSecurityAction.AddEmailBackup -> repository.addEmailBackup().also { r ->
            if (r is SecurityResult.Success) {
                _state.update { it.copy(flow = null, overview = it.overview.withEmailBackup(enabled = true)) }
            }
        }
        PendingSecurityAction.RemoveEmailBackup -> repository.removeEmailBackup().also { r ->
            if (r is SecurityResult.Success) {
                _state.update { it.copy(flow = null, overview = it.overview.withEmailBackup(enabled = false)) }
            }
        }
    }

    /** The non-success routing shared by first attempts and step-up resumes. */
    private fun applyOutcome(action: PendingSecurityAction, outcome: SecurityResult<*>) {
        when (outcome) {
            is SecurityResult.Success -> Unit // runAction applied the step transition already
            // The server's freshness refusal: open (or re-open) the step-up sheet carrying the
            // pending action — a resume can itself be refused if the stamp expired mid-flow.
            SecurityResult.StepUpRequired ->
                _state.update { it.copy(flow = SecuritySettings.Flow.StepUp(action)) }
            SecurityResult.Rejected -> when (action) {
                // A wrong/expired TOTP code: back to code entry against the SAME secret (no new QR).
                is PendingSecurityAction.VerifyCode -> _state.update {
                    it.copy(flow = SecuritySettings.Flow.EnterCode(action.enrollment, wrongCode = true))
                }
                // No other gated action produces Rejected today — treat defensively as a failure.
                else -> _state.update { it.copy(lastActionFailed = true, flow = null) }
            }
            SecurityResult.Unauthorized, SecurityResult.Unavailable ->
                // Transient (or a PAT 401 the repository already routed to re-auth): keep the current
                // step so the person can retry in place, and surface the failure flag.
                _state.update { it.copy(lastActionFailed = true) }
        }
    }
}

/** In-place summary flip after a successful enroll — a non-[SecuritySettings.Overview.Ready] summary becomes Ready. */
private fun SecuritySettings.Overview.withTotp(enabled: Boolean): SecuritySettings.Overview = when (this) {
    is SecuritySettings.Overview.Ready -> copy(totpEnabled = enabled)
    else -> SecuritySettings.Overview.Ready(totpEnabled = enabled, emailBackup = false)
}

/** In-place backup flip after a successful add/remove; a non-Ready summary stays as-is. */
private fun SecuritySettings.Overview.withEmailBackup(enabled: Boolean): SecuritySettings.Overview = when (this) {
    is SecuritySettings.Overview.Ready -> copy(emailBackup = enabled)
    else -> this
}

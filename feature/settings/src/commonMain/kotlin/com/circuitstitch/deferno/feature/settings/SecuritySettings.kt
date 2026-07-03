package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.TotpEnrollment

/**
 * The Settings Destination's view of the **Security & 2FA** category (#72 follow-through) — the
 * native port of the web app's SecurityPane over the same first-party backend contract
 * (CONTRACT-NOTES → "Security & 2FA"). Server truth, fetched fresh on each drill-in (never cached:
 * the factor list is the same one the sign-in challenge reads, and a stale "2FA off" summary for an
 * enrolled user is exactly the bug the status read exists to prevent).
 *
 * [overview] is the second-factor summary; [devices] the connected native installs; [flow] the
 * modal step the View renders *over* the summary while an enrollment/step-up is in progress. At
 * most one mutation is in flight ([busy] — intents are no-ops while set); [lastActionFailed] is
 * the transient "couldn't reach the server" signal, cleared on the next intent.
 */
data class SecuritySettings(
    val overview: Overview = Overview.Loading,
    val devices: Devices = Devices.Loading,
    val flow: Flow? = null,
    val busy: Boolean = false,
    val lastActionFailed: Boolean = false,
) {
    /** The 2FA summary (`GET /auth/mfa/status`). */
    sealed interface Overview {
        data object Loading : Overview

        /** Offline / server unavailable / unwired host — the View offers retry, never a dead tap. */
        data object Unavailable : Overview

        data class Ready(val totpEnabled: Boolean, val emailBackup: Boolean) : Overview
    }

    companion object {
        /** In-place summary flip after a successful enroll — a non-[Overview.Ready] summary becomes Ready. */
        fun Overview.withTotp(enabled: Boolean): Overview = when (this) {
            is Overview.Ready -> copy(totpEnabled = enabled)
            else -> Overview.Ready(totpEnabled = enabled, emailBackup = false)
        }

        /** In-place backup flip after a successful add/remove; a non-Ready summary stays as-is. */
        fun Overview.withEmailBackup(enabled: Boolean): Overview = when (this) {
            is Overview.Ready -> copy(emailBackup = enabled)
            else -> this
        }
    }

    /** The connected-devices list (`GET /auth/connected-devices`). */
    sealed interface Devices {
        data object Loading : Devices
        data object Unavailable : Devices

        /** [activeTokenId] marks "this device" (its row is not revocable here — sign-out owns that). */
        data class Ready(val devices: List<ConnectedDevice>, val activeTokenId: String?) : Devices
    }

    /**
     * The modal flow step. The shape mirrors the webui's step machine: any gated mutation can be
     * interrupted by [StepUp] (the server's 403 freshness refusal) and **resumes the exact pending
     * action** after a successful password re-verification — including re-verifying the same code
     * against the same secret with no fresh enrollment.
     */
    sealed interface Flow {
        /** Password re-entry gating [pending]; [wrongPassword] after a rejected attempt. */
        data class StepUp(
            val pending: PendingSecurityAction,
            val wrongPassword: Boolean = false,
        ) : Flow

        /** TOTP enrollment: the secret/URI to add to an authenticator + the 6-digit code entry. */
        data class EnterCode(
            val enrollment: TotpEnrollment,
            val wrongCode: Boolean = false,
        ) : Flow

        /**
         * The one-shot recovery codes (shown exactly once — the backend stores only hashes). The
         * only exit is explicit acknowledgment; the View must not offer back/dismiss around it.
         */
        data class RecoveryCodes(val codes: List<String>) : Flow
    }
}

/**
 * A gated mutation awaiting (or resumed after) step-up — the webui's PendingAction tag, typed.
 * [VerifyCode] carries its inputs so the resume re-runs the *same* verification (same secret, same
 * code) rather than restarting enrollment.
 */
sealed interface PendingSecurityAction {
    data object EnrollTotp : PendingSecurityAction
    data object DisableMfa : PendingSecurityAction
    data object AddEmailBackup : PendingSecurityAction
    data object RemoveEmailBackup : PendingSecurityAction
    data class VerifyCode(val enrollment: TotpEnrollment, val code: String) : PendingSecurityAction
}

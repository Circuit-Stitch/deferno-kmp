package com.circuitstitch.deferno.core.data.security

import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment

/**
 * The outcome of one account-security call (CONTRACT-NOTES → "Security & 2FA"). Five disjoint cases
 * the Settings slice reacts to differently — like [com.circuitstitch.deferno.core.data.auth.MeResult],
 * the distinction is the whole point of the type:
 *
 * - [Success] — the typed payload.
 * - [StepUpRequired] — HTTP `403` with error code `step_up_required`: the mutation needs a fresh
 *   password re-verification first ([SecurityRemoteSource.stepUp]), then a retry of the same call.
 *   The step-up window is short (server-enforced, ~5 min), so any mutation can hit this at any time.
 * - [Rejected] — the server understood and refused the *input*: a wrong TOTP code on enroll-verify
 *   (`400 invalid code`) or a wrong password on step-up (`401 invalid credentials` — note step-up's
 *   401 means bad password, not an expired PAT; the PAT authenticated fine to reach the handler).
 * - [Unauthorized] — HTTP `401` on any *other* call: the Active Account's PAT is invalid/expired.
 *   The repository turns this into a re-auth request scoped to that Account (ADR-0002).
 * - [Unavailable] — transport/TLS, 5xx (including the 503 the backend returns when its IdP
 *   integration is unconfigured), or an out-of-window envelope: transient, retry is the right response.
 *
 * Deliberately free of `core:network` types — wire error shapes are condensed here at the source.
 */
sealed interface SecurityResult<out T> {
    data class Success<T>(val value: T) : SecurityResult<T>
    data object StepUpRequired : SecurityResult<Nothing>
    data object Rejected : SecurityResult<Nothing>
    data object Unauthorized : SecurityResult<Nothing>
    data object Unavailable : SecurityResult<Nothing>
}

/**
 * The account-security network port (Security & 2FA, #72 follow-through): the first-party MFA
 * management endpoints (`/auth/mfa/…`, `POST /auth/step-up`) and the connected-devices token list.
 * All calls run as the Active Account (the shared client's bearer plugin, ADR-0012); wire DTOs and
 * error shapes are condensed to [SecurityResult] at this boundary (ADR-0011).
 *
 * **Step-up freshness rides the session cookie** (a backend web-session concept): a successful
 * [stepUp] response carries a `Set-Cookie` the implementation must echo on every subsequent
 * mutating call. The cookie is held in memory only and scoped to this source's lifetime — the
 * source is AccountScope, so an Account switch tears it down (a step-up stamp is *not* user-bound
 * server-side; scoping the cookie per Account session is what keeps it from leaking across accounts).
 */
interface SecurityRemoteSource {
    /** `GET /auth/mfa/status` — current second-factor enrollment. Never step-up gated. */
    suspend fun fetchStatus(): SecurityResult<MfaStatus>

    /**
     * `POST /auth/step-up` — re-verify the account password and stamp the session with step-up
     * freshness. [SecurityResult.Rejected] means the password was wrong (or the attempt budget is
     * exhausted — the server deliberately doesn't distinguish).
     */
    suspend fun stepUp(password: String): SecurityResult<Unit>

    /** `POST /auth/mfa/enroll/start` — begin (or restart/replace) TOTP enrollment. Step-up gated. */
    suspend fun enrollStart(): SecurityResult<TotpEnrollment>

    /**
     * `POST /auth/mfa/enroll/verify` — complete TOTP enrollment with the authenticator's 6-digit
     * [code]. Success carries the 10 single-use recovery codes, shown exactly once. Step-up gated;
     * [SecurityResult.Rejected] means the code didn't verify (typo/expired window — re-enter, same secret).
     */
    suspend fun enrollVerify(code: String): SecurityResult<List<String>>

    /** `POST /auth/mfa/backup/add` — opt into the email-OTP backup factor. Step-up gated. */
    suspend fun addEmailBackup(): SecurityResult<Unit>

    /** `POST /auth/mfa/backup/remove` — remove only the email backup factor (idempotent). Step-up gated. */
    suspend fun removeEmailBackup(): SecurityResult<Unit>

    /**
     * `POST /auth/mfa/disable` — fully disable 2FA: removes TOTP + email backup + recovery codes.
     * Step-up gated. Idempotent server-side, so a retry after [SecurityResult.Unavailable] converges.
     */
    suspend fun disableMfa(): SecurityResult<Unit>

    /** `GET /auth/connected-devices` — the native installs signed into this account (token metadata). */
    suspend fun fetchConnectedDevices(): SecurityResult<List<ConnectedDevice>>

    /**
     * `DELETE /auth/tokens/{tokenId}` as the **Active Account's** bearer — revoke another device's
     * token (signs that device out; it must sign in through the browser again). Distinct from the
     * sign-out self-revoke ([com.circuitstitch.deferno.core.data.auth.AuthRemoteSource.revokeToken],
     * which authenticates with the very token it deletes). Not step-up gated server-side.
     */
    suspend fun revokeDevice(tokenId: String): SecurityResult<Unit>
}

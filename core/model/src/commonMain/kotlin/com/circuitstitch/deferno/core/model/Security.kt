package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * The Active Account's second-factor enrollment, read from `GET /auth/mfa/status` (the Security &
 * 2FA screen's server truth — the same Zitadel authentication-methods list the sign-in challenge
 * reads, so the display can never drift from actual challenge behavior).
 *
 * - [totpEnabled] — the primary 2FA method (an authenticator app) is enrolled (`mfa_enabled`).
 * - [emailBackup] — the opt-in email-OTP backup factor is enrolled.
 */
data class MfaStatus(
    val totpEnabled: Boolean,
    val emailBackup: Boolean,
)

/**
 * A freshly-started TOTP enrollment (`POST /auth/mfa/enroll/start`): the shared [secret] for manual
 * authenticator entry and the full otpauth:// provisioning [uri] (QR payload / authenticator-app
 * deep link). Held only for the duration of the enroll flow — never persisted.
 */
data class TotpEnrollment(
    val secret: String,
    val uri: String,
)

/**
 * A device signed into this account (`GET /auth/connected-devices`): a native install's bearer
 * token, viewed as metadata only (the raw token is never returned after mint). [id] is the token id
 * `DELETE /auth/tokens/{id}` revokes; matching it against the local `Account.tokenId` marks
 * "this device". [lastUsedAt] is null when the token has never been presented.
 */
data class ConnectedDevice(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the account-security surface (CONTRACT-NOTES → "Security & 2FA") — the first-party
 * MFA management endpoints under `/auth/mfa/…` plus the connected-devices token list. Faithful flat
 * snake_case shapes decoded by the tolerant reader ([com.circuitstitch.deferno.core.network.DefernoJson]),
 * so additive backend fields pass through. Must parse the `contracts/fixtures/security-*.json` goldens (#19).
 */

/** `GET /auth/mfa/status` — the caller's current second-factor enrollment. */
@Serializable
data class MfaStatusDto(
    @SerialName("mfa_enabled") val mfaEnabled: Boolean,
    @SerialName("email_backup") val emailBackup: Boolean,
)

/** `POST /auth/mfa/enroll/start` — the fresh TOTP secret + otpauth provisioning URI. */
@Serializable
data class MfaEnrollStartDto(
    val secret: String,
    val uri: String,
)

/**
 * `POST /auth/mfa/enroll/verify` — enrollment completed; [recoveryCodes] are the 10 single-use
 * lockout-safety codes, shown to the user exactly once (the backend stores only their hashes).
 */
@Serializable
data class MfaEnrollVerifyDto(
    @SerialName("mfa_enabled") val mfaEnabled: Boolean,
    val primary: String? = null,
    @SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList(),
)

/** `POST /auth/step-up` — the fresh re-auth stamp (epoch seconds); the gate itself rides the session cookie. */
@Serializable
data class StepUpDto(
    @SerialName("stepped_up_at") val steppedUpAt: Long,
)

/** `POST /auth/step-up` request: the account password, re-verified against the IdP. */
@Serializable
data class StepUpRequest(
    val password: String,
)

/** `POST /auth/mfa/enroll/verify` request: the 6-digit code from the authenticator app. */
@Serializable
data class MfaEnrollVerifyRequest(
    val code: String,
)

/**
 * One row of `GET /auth/connected-devices` (and `GET /auth/tokens`): an API token viewed as
 * metadata only — `ApiTokenView { id, name, kind, created_at, client_id?, last_used_at? }`.
 * Timestamps are ISO-8601 UTC strings; [kind] is `"user"` or `"mcp"` (kept as the raw string —
 * tolerant to future kinds).
 */
@Serializable
data class ApiTokenViewDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

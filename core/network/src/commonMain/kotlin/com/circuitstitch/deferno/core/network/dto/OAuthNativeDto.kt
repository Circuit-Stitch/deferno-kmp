package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the native browser sign-in flow — OAuth Authorization Code + PKCE → personal access
 * token (backend #299, ADR-0012/0026). These endpoints are **not** in `openapi-0.1.json` (they're the
 * browser-redirect / RFC-standard bootstrap, outside the data API); shapes are pinned from the live
 * backend in `contracts/CONTRACT-NOTES.md` → "Native browser sign-in". Faithful flat wire DTOs decoded
 * by the tolerant reader ([com.circuitstitch.deferno.core.network.DefernoJson]) so additive fields pass.
 */

/**
 * RFC 7591 dynamic client registration request. The native app is a **public** client (a shipped
 * binary holds no secret), so [tokenEndpointAuthMethod] is `"none"` and the token endpoint authenticates
 * the *flow* via PKCE. [redirectUris] must be on the backend allowlist (custom scheme / verified
 * App-Link / loopback).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientRegistrationRequest(
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("client_name") val clientName: String,
    // Forced onto the wire even though they're constants: DefernoJson has encodeDefaults=off, and the
    // public-client contract (`token_endpoint_auth_method: "none"`) must be explicit, not implied.
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("application_type") val applicationType: String = "native",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
)

/** RFC 7591 registration response (envelope `data`). Only [clientId] is load-bearing for the client. */
@Serializable
data class ClientRegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String? = null,
)

/**
 * Token-exchange request: the one-time authorization [code] plus the PKCE [codeVerifier] proving
 * possession. [clientId] **and** [redirectUri] are both required and must match the code's server-side
 * binding (verified live — the ADR's `{code, code_verifier, name?}` is incomplete). [name] tags the
 * minted token to this device (e.g. "Deferno Android — Pixel 8").
 */
@Serializable
data class NativeTokenRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val name: String? = null,
)

/**
 * The mint response (`CreateApiTokenResponse` = `ApiTokenView` + `token`, modelled flat — kotlinx has
 * no `allOf`). The raw [token] is returned **once**; [id] is the server-side token id that enables
 * revoke-on-sign-out (`DELETE /auth/tokens/{id}`, ADR-0023 amendment / #310).
 */
@Serializable
data class CreateApiTokenResponseDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    val token: String,
)

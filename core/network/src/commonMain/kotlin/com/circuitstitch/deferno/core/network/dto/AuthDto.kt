package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `GET /auth/me` payload (CONTRACT-NOTES → "Auth"): the authenticated user's identity plus the
 * Org isolation key. Modelled as a faithful flat wire DTO — snake_case keys carried on [SerialName],
 * decoded by the tolerant reader ([com.circuitstitch.deferno.core.network.DefernoJson]) so additive
 * backend fields pass through. Must parse `contracts/fixtures/auth-me.json` (#19).
 *
 * [personalOrgId] (the spec's `personal_org_id` / `owner_org_id`) is the Org isolation key every
 * per-Account row is scoped by (ADR-0002). The domain entity this maps to earns its own issue (the
 * `/auth/me` tracer, #20); for now this is the lossless wire shape the contract-fixture harness
 * asserts against. Identity-critical fields are required (their absence *should* fail parsing);
 * the optional [consoleUrl] / defaulted [isAdmin] stay tolerant.
 */
@Serializable
data class AuthenticatedUserDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("personal_org_id") val personalOrgId: String,
    @SerialName("org_slug") val orgSlug: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("console_url") val consoleUrl: String? = null,
)

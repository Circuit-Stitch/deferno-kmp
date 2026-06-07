package com.circuitstitch.deferno.core.model

/**
 * The authenticated backend identity an [Account] signs in as — the domain model of `GET /auth/me`
 * (CONTEXT.md → "User", ADR-0012). One Account ⇄ one backend [User]; this is the server-side identity
 * (`/auth/me`), kept **distinct** from the client-side [Account] (the hard isolation boundary) and
 * from an [OrgId] (a within-Account filter). The network boundary maps the wire DTO onto this clean
 * shape (ADR-0011, "condense at the edge"), so nothing above `core:network` sees a snake_case field.
 *
 * - [id] — the backend User UUID ([UserId]); the identity the Account authenticates as.
 * - [username] / [displayName] — the login handle and the human-facing name (what a screen renders).
 * - [role] / [isAdmin] — the server's authorization signals, carried faithfully. They are partly
 *   redundant on the wire (`role: "admin"` vs `is_admin: false`); both are preserved rather than
 *   reconciled here, since the contract ships both and their precise relationship is the server's.
 * - [personalOrgId] — the **Org isolation key** ([OrgId], ADR-0002): every per-Account row is scoped
 *   by it, so hydrating the Active Account from `/auth/me` makes the personal Org resolvable.
 * - [orgSlug] — the short personal-Org slug (`u-e4h2qk`) used in human-facing item `ref`s.
 * - [consoleUrl] — an optional link to the web admin console; absent for non-admin users.
 */
data class User(
    val id: UserId,
    val username: String,
    val displayName: String,
    val role: String,
    val personalOrgId: OrgId,
    val orgSlug: String,
    val isAdmin: Boolean,
    val consoleUrl: String?,
)

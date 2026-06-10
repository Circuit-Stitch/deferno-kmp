package com.circuitstitch.deferno.core.model

/**
 * One Deferno identity the person has signed into the app (ADR-0002, CONTEXT.md): the unit they
 * add, remove, and switch between. Accounts are the **hard isolation boundary** — each has its own
 * bearer token and its own encrypted local data, and nothing ever crosses between them.
 *
 * Deliberately minimal in v1:
 * - [id] is the partition key (vault token, encrypted DB, registry row all key off it).
 * - [label] is the human-facing name shown in the account switcher (e.g. "Work", "Personal").
 * - [tokenId] is the server-side id of this Account's bearer token, when known — present for
 *   browser-minted tokens (ADR-0026), `null` for pasted dev tokens (which carry no id). It enables
 *   server-side revoke on sign-out (`DELETE /auth/tokens/{id}`); it is a non-secret opaque reference
 *   (the API treats token ids as safe-to-return), so unlike the token itself it may live here.
 *
 * What is **not** here, on purpose:
 * - No bearer token — the secret itself lives only in the secure vault, never alongside loggable
 *   model data (ADR-0009: never co-locate or log tokens/PII). [toString] of an Account is safe to log.
 * - No `owner_org_id` — Org is a *soft filter within* an Account, not part of its identity
 *   (ADR-0002); it lands with the Org-selection work, out of scope here.
 * - No backend-User fields (username, `personal_org_id`, …) — those come from `GET /auth/me` and
 *   are auth/Org enrichment, added when a feature needs them. New fields append without breaking
 *   callers because everything flows through the AccountManager API, not this shape.
 */
data class Account(
    val id: AccountId,
    val label: String,
    val tokenId: String? = null,
)

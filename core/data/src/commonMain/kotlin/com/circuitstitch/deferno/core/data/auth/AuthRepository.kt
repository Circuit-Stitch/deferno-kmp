package com.circuitstitch.deferno.core.data.auth

/**
 * The identity repository the feature layer depends on (#20). It owns the one decision the layers
 * above shouldn't: that an expired session ([MeResult.Unauthorized]) must route the Active Account
 * to re-auth. The component calls [loadMe] and renders the returned [MeResult]; the re-auth side
 * effect (scoped to the Active Account, ADR-0002) is handled here, not in the UI.
 *
 * [DefaultAuthRepository] is the production implementation; the tracer (#20) is a one-shot fetch, so
 * this is a single suspend command rather than the local-`Flow`-backed shape the Task/Plan
 * repositories use (there is no `/auth/me` cache yet — the identity is fetched live per scene).
 */
interface AuthRepository {
    /**
     * Fetches the Active Account's identity. On [MeResult.Unauthorized] it also raises a re-auth
     * request for the Active Account (and *only* that Account) before returning.
     */
    suspend fun loadMe(): MeResult
}

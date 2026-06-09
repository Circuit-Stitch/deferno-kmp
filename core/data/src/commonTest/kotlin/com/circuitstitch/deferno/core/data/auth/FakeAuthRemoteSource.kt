package com.circuitstitch.deferno.core.data.auth

/**
 * Test [AuthRemoteSource] that returns a programmed [MeResult] and counts how many times it was
 * called — so a repository/component test can drive each `/auth/me` outcome (authenticated,
 * unauthorized, unavailable) and assert how the layer above reacts, without any HTTP plumbing.
 *
 * The candidate-token [fetchMe] (#15, ADR-0023) records the [lastToken] it was handed and returns
 * [candidateResult] when set (else the same [result]) — so a SignInService test can drive a bad
 * pasted token independently of the Active-Account path.
 */
class FakeAuthRemoteSource(var result: MeResult) : AuthRemoteSource {
    var calls: Int = 0
        private set

    /** The token passed to the most recent candidate-token [fetchMe], or null if never called that way. */
    var lastToken: String? = null
        private set

    /** Outcome for the candidate-token [fetchMe]; falls back to [result] when null. */
    var candidateResult: MeResult? = null

    override suspend fun fetchMe(): MeResult {
        calls++
        return result
    }

    override suspend fun fetchMe(token: String): MeResult {
        calls++
        lastToken = token
        return candidateResult ?: result
    }
}

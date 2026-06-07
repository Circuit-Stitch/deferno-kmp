package com.circuitstitch.deferno.core.data.auth

/**
 * Test [AuthRemoteSource] that returns a programmed [MeResult] and counts how many times it was
 * called — so a repository/component test can drive each `/auth/me` outcome (authenticated,
 * unauthorized, unavailable) and assert how the layer above reacts, without any HTTP plumbing.
 */
class FakeAuthRemoteSource(var result: MeResult) : AuthRemoteSource {
    var calls: Int = 0
        private set

    override suspend fun fetchMe(): MeResult {
        calls++
        return result
    }
}

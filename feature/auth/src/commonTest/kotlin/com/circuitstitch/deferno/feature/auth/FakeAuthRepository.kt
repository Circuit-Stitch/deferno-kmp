package com.circuitstitch.deferno.feature.auth

import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult

/**
 * Test [AuthRepository] that returns a programmed [MeResult] and counts loads, so the component test
 * can drive each `/auth/me` outcome and assert the state it maps to — plus that [AuthComponent.onRetry]
 * actually re-fetches. The re-auth routing is the repository's own concern (tested in `core:data`),
 * so this fake omits it.
 */
class FakeAuthRepository(var result: MeResult) : AuthRepository {
    var loadCount: Int = 0
        private set

    override suspend fun loadMe(): MeResult {
        loadCount++
        return result
    }
}

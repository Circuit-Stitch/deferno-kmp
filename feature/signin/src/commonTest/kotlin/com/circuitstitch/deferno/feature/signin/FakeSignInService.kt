package com.circuitstitch.deferno.feature.signin

import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import kotlinx.coroutines.CompletableDeferred

/**
 * Test [SignInService] that records every token it was handed and returns a programmed [result] — so
 * the component test can drive each outcome (success / invalid / unavailable) and assert the state it
 * maps to, plus that the token was trimmed before submission. When [gate] is set, `signIn` suspends on
 * it before returning, so a test can observe the in-flight `isValidating` state deterministically.
 */
class FakeSignInService(var result: SignInResult) : SignInService {
    val tokens = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun signIn(token: String): SignInResult {
        tokens += token
        gate?.await()
        return result
    }
}

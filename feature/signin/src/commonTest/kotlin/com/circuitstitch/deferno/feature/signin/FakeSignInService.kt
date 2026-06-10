package com.circuitstitch.deferno.feature.signin

import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import kotlinx.coroutines.CompletableDeferred

/**
 * Test [SignInService] that records every pasted token, counts browser sign-ins, and returns a
 * programmed [result] — so the component test can drive each outcome (success / invalid / unavailable /
 * cancelled) for both paths and assert the state it maps to. When [gate] is set, both paths suspend on
 * it before returning, so a test can observe the in-flight `isBusy` state deterministically.
 * [browserResult] overrides [result] for the browser path when set.
 */
class FakeSignInService(var result: SignInResult) : SignInService {
    val tokens = mutableListOf<String>()
    var browserCalls = 0
        private set
    var gate: CompletableDeferred<Unit>? = null

    /** Outcome for [signInWithBrowser]; falls back to [result] when null. */
    var browserResult: SignInResult? = null

    override suspend fun signInWithBrowser(): SignInResult {
        browserCalls++
        gate?.await()
        return browserResult ?: result
    }

    override suspend fun signIn(token: String): SignInResult {
        tokens += token
        gate?.await()
        return result
    }
}

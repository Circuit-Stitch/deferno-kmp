package com.circuitstitch.deferno.core.data.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AuthRedirectInbox] (ADR-0026, #137): the shared one-shot rendezvous between the host OS layer's
 * `publish` and the in-flight mobile BrowserAuthenticator's `expect`. One outstanding request at a
 * time (modal sign-in): a new `expect` cancels the stale one, a `publish` is consumed exactly once,
 * and `clear` disarms without completing.
 */
class AuthRedirectInboxTest {

    private val inbox = AuthRedirectInbox()

    @Test
    fun publishCompletesTheExpectedDeferredWithTheRedirectUri() = runTest {
        val deferred = inbox.expect()
        inbox.publish("com.circuitstitch.deferno://auth?code=c1&state=s1")
        assertEquals("com.circuitstitch.deferno://auth?code=c1&state=s1", deferred.await())
    }

    @Test
    fun aNewExpectCancelsTheStaleDeferred() {
        val stale = inbox.expect()
        val fresh = inbox.expect()
        assertTrue(stale.isCancelled, "a new expect() must cancel the stale rendezvous")
        assertFalse(fresh.isCancelled)
    }

    @Test
    fun publishAfterAReplacedExpectCompletesOnlyTheFreshDeferred() = runTest {
        val stale = inbox.expect()
        val fresh = inbox.expect()
        inbox.publish("com.circuitstitch.deferno://auth?code=c2")
        assertTrue(stale.isCancelled)
        assertEquals("com.circuitstitch.deferno://auth?code=c2", fresh.await())
    }

    @Test
    fun publishWithNothingWaitingIsANoOp() {
        inbox.publish("com.circuitstitch.deferno://auth?code=stray")
        // A later expect must not receive the stray redirect — the inbox never buffers.
        val deferred = inbox.expect()
        assertFalse(deferred.isCompleted)
    }

    @Test
    fun publishIsConsumedExactlyOnce() = runTest {
        val first = inbox.expect()
        inbox.publish("com.circuitstitch.deferno://auth?code=once")
        assertEquals("com.circuitstitch.deferno://auth?code=once", first.await())
        // The rendezvous disarmed itself: a second publish has no waiter to hit.
        val second = inbox.expect()
        assertFalse(second.isCompleted)
    }

    @Test
    fun clearDisarmsWithoutCompleting() {
        val deferred = inbox.expect()
        inbox.clear()
        inbox.publish("com.circuitstitch.deferno://auth?code=late")
        assertFalse(deferred.isCompleted, "a cleared rendezvous must never receive a late publish")
        assertFalse(deferred.isCancelled, "clear() disarms; cancellation stays with the awaiting caller")
    }
}

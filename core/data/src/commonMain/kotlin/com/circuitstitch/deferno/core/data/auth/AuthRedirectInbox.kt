package com.circuitstitch.deferno.core.data.auth

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred

/**
 * One-shot rendezvous for the captured OAuth redirect (ADR-0026, #137): the host OS layer (Android's
 * `MainActivity` intent handler, the iOS Swift URL handler via `DefernoRoot.forwardAuthRedirect`)
 * [publish]es the redirect URI the system browser returned with, and the in-flight mobile
 * [BrowserAuthenticator] awaits the deferred it took from [expect]. An AppScope singleton injected
 * into both ends — never ambient process-global state. (Desktop captures via its loopback listener
 * and needs no inbox.)
 *
 * Sign-in is modal, so one outstanding request at a time — a new [expect] cancels any stale one.
 * Thread-safe across all targets: publish lands on the main thread while the authenticator expects
 * from a background dispatcher, so the hand-off swings on a single atomic exchange.
 */
@OptIn(ExperimentalAtomicApi::class)
class AuthRedirectInbox {
    private val pending = AtomicReference<CompletableDeferred<String>?>(null)

    /** Arm the rendezvous and return the deferred the authenticator awaits. */
    fun expect(): CompletableDeferred<String> =
        CompletableDeferred<String>().also { fresh -> pending.exchange(fresh)?.cancel() }

    /** Deliver a captured redirect URI to the waiting authenticator (a no-op if none is waiting). */
    fun publish(redirectUri: String) {
        pending.exchange(null)?.complete(redirectUri)
    }

    /** Disarm without completing — the authenticator's failure/cancellation cleanup. */
    fun clear() {
        pending.store(null)
    }
}

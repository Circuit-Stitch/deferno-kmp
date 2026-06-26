package com.circuitstitch.deferno.core.data.account

import app.cash.turbine.test
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DefaultReauthCoordinator] (#20): the process-global re-auth signal delivers each flagged Account
 * to subscribers in request order. A re-auth is an event (no replay), so this asserts live delivery
 * to an active subscriber — Turbine starts collecting (UNDISPATCHED) before the emissions. JVM-fast
 * path (ADR-0006).
 */
class ReauthCoordinatorTest {

    @Test
    fun deliversEachRequestedAccountToSubscribersInOrder() = runTest {
        val coordinator = DefaultReauthCoordinator()

        coordinator.events.test {
            coordinator.requestReauth(AccountId("acc-1"))
            assertEquals(AccountId("acc-1"), awaitItem())

            coordinator.requestReauth(AccountId("acc-2"))
            assertEquals(AccountId("acc-2"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sessionExpiredIsSetByA401AndClearedByTheNext2xx() = runTest {
        val coordinator = DefaultReauthCoordinator()
        assertEquals(false, coordinator.sessionExpired.value)

        // The network sink reports an Active-Account 401 → the read surfaces should banner.
        coordinator.onActiveSessionUnauthorized()
        assertEquals(true, coordinator.sessionExpired.value)

        // A later successful Active-Account request (e.g. after signing back in) self-clears it (AC #4).
        coordinator.onActiveSessionAuthorized()
        assertEquals(false, coordinator.sessionExpired.value)
    }

    @Test
    fun requestReauthAlsoRaisesTheRetainedSessionExpiredFlag() = runTest {
        // The /auth/me 401 path raises an explicit re-auth request; the retained flag must agree so a
        // surface mounted after the prompt still sees the banner (unlike the one-shot `events`).
        val coordinator = DefaultReauthCoordinator()

        coordinator.requestReauth(AccountId("acc-1"))

        assertEquals(true, coordinator.sessionExpired.value)
    }

    @Test
    fun doesNotReplayAFlagRaisedBeforeAnyoneSubscribed() = runTest {
        val coordinator = DefaultReauthCoordinator()

        // A re-auth is an event, not retained state (no replay): one raised with no active subscriber
        // is dropped, so a freshly-mounted Auth shell does not re-fire a stale prompt for an Account
        // flagged earlier. Guards the deliberate `replay = 0` choice against a `replay = 1` regression.
        coordinator.requestReauth(AccountId("stale"))

        coordinator.events.test {
            expectNoEvents() // the pre-subscription flag was not replayed to this late subscriber
            coordinator.requestReauth(AccountId("fresh"))
            assertEquals(AccountId("fresh"), awaitItem()) // only post-subscription events arrive
            cancelAndIgnoreRemainingEvents()
        }
    }
}

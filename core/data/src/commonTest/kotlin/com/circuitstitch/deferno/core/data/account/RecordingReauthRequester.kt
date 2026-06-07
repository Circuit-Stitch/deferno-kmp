package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.AccountId

/**
 * Test [ReauthRequester] that records every Account flagged for re-auth, in order — so a test can
 * assert a `401` raised a request for *exactly* the Active Account and left every sibling untouched
 * (ADR-0002 hard isolation). Avoids the [DefaultReauthCoordinator]'s `SharedFlow` timing in tests
 * that only care *which* Accounts were flagged (the coordinator's emission has its own test).
 */
class RecordingReauthRequester : ReauthRequester {
    /** Every Account passed to [requestReauth], in order. */
    val requested = mutableListOf<AccountId>()

    override fun requestReauth(account: AccountId) {
        requested += account
    }
}

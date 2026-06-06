package com.circuitstitch.deferno.core.model

import kotlin.jvm.JvmInline

/**
 * Stable identifier of an [Account] (ADR-0002) — the hard isolation boundary. It keys that
 * Account's bearer token in the secure vault, its encrypted local database, and its row in the
 * account registry; each one is stored, read, and wiped independently and nothing ever crosses
 * between Accounts.
 *
 * Lives in `core:model` (the dependency-free foundation) so the [Account] model, the data layer
 * (AccountManager + its ports), and the DI scope graph can all name identity without depending on
 * the secure-storage capability module — `core:secure` and the rest depend up onto `core:model`.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }
}

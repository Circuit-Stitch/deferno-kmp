package com.circuitstitch.deferno

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId

/** A dev Account paired with the PAT to seed it with (#68, ADR-0012). */
internal data class DevAccount(val account: Account, val token: String)

/**
 * Parses the dev-account PATs surfaced from `local.properties` into [BuildConfig] (#68, ADR-0012) — the
 * interim dev login placeholder until real sign-in (#15). [DefernoApplication] seeds these into the
 * `AccountManager` on a debug build, so the app opens on real staging data.
 *
 * Both inputs are blank in a release build (the BuildConfig fields are empty there), so [from] returns
 * an empty list and nothing is ever seeded in production — no PAT ships.
 */
internal object DevAccounts {
    /** Id used for the back-compat single-token account. */
    private val DEFAULT_ID = AccountId("dev")

    /**
     * Build the dev accounts from the two BuildConfig fields:
     *  - [devAccounts]: a `';'`-separated list of `id:label:token` entries (label may be blank → falls
     *    back to id; the token keeps any `:` it contains, split with limit 3). Malformed entries
     *    (missing id or token) are skipped.
     *  - [stagingToken]: the legacy single PAT — seeds one "Dev (staging)" account, unless an explicit
     *    entry already claims the [DEFAULT_ID] id.
     *
     * Later entries win on duplicate ids (a `local.properties` typo shouldn't seed two of the same
     * Account), preserving first-seen order.
     */
    fun from(devAccounts: String, stagingToken: String): List<DevAccount> {
        val parsed = devAccounts.split(';')
            .mapNotNull { entry ->
                val parts = entry.split(':', limit = 3).map { it.trim() }
                if (parts.size < 3) return@mapNotNull null
                val (id, label, token) = parts
                if (id.isEmpty() || token.isEmpty()) return@mapNotNull null
                DevAccount(Account(AccountId(id), label.ifEmpty { id }), token)
            }

        val all = if (stagingToken.isNotBlank() && parsed.none { it.account.id == DEFAULT_ID }) {
            parsed + DevAccount(Account(DEFAULT_ID, "Dev (staging)"), stagingToken.trim())
        } else {
            parsed
        }

        // De-dupe by id, last writer wins, original order preserved.
        return all.associateBy { it.account.id }.values.toList()
    }
}

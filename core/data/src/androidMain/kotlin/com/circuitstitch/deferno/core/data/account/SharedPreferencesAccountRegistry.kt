package com.circuitstitch.deferno.core.data.account

import android.content.Context
import android.content.SharedPreferences
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId

/**
 * Persistent [AccountRegistry] backed by [SharedPreferences] (ADR-0002 / ADR-0014): the durable
 * roster + active-account selection that survives process restarts, so a cold start restores the
 * Active Account without re-authenticating. Holds only **non-secret metadata** (ids + labels) —
 * bearer tokens stay in the SecretVault, per-Account data in the encrypted DB. Insertion order is
 * preserved via [AccountRosterCodec].
 *
 * Excluded from OS backup (see `res/xml` backup rules): the roster is a device-bound cache; a
 * restored device starts clean and re-authenticates (ADR-0009), since the tokens + DB keys it
 * references are non-exportable. Runs only on a device → excluded from the headless coverage gate;
 * the order-/round-trip logic is measured via [AccountRosterCodec] in commonTest.
 */
class SharedPreferencesAccountRegistry(
    context: Context,
    prefsName: String = DEFAULT_PREFS_NAME,
) : AccountRegistry {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun all(): List<Account> =
        AccountRosterCodec.decode(prefs.getString(KEY_ROSTER, null))

    override suspend fun put(account: Account) {
        val roster = all().toMutableList()
        val at = roster.indexOfFirst { it.id == account.id }
        if (at >= 0) roster[at] = account else roster.add(account) // upsert, preserving position
        writeRoster(roster)
    }

    override suspend fun remove(id: AccountId) {
        writeRoster(all().filterNot { it.id == id })
    }

    override suspend fun activeId(): AccountId? =
        prefs.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }?.let(::AccountId)

    override suspend fun setActive(id: AccountId?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id.value)
        }.apply()
    }

    private fun writeRoster(roster: List<Account>) {
        prefs.edit().putString(KEY_ROSTER, AccountRosterCodec.encode(roster)).apply()
    }

    private companion object {
        // Keep in sync with the backup-rule excludes in app/androidApp/src/main/res/xml.
        const val DEFAULT_PREFS_NAME = "deferno_account_roster"
        const val KEY_ROSTER = "roster"
        const val KEY_ACTIVE = "active"
    }
}

package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.coroutines.flow.StateFlow

/**
 * The multi-account boundary (ADR-0002): add / remove / list / switch the Deferno [Account]s on
 * this device, with hard isolation between them. A process-global singleton shared across every
 * window/scene (ADR-0008 G2) — the one authority on which Account is active.
 *
 * Isolation is enforced by delegating each Account's secrets and data to per-Account-keyed
 * collaborators: the bearer token to the SecretVault, the local data to an [AccountDataStore], the
 * roster to an [AccountRegistry]. Removal secure-wipes both token and data (ADR-0009).
 *
 * Observation is Flow-only (ADR-0001): [activeAccount] (inherited from [AccountContext]) and
 * [accounts] are the reactive surface the UI and repositories observe. Switching ([switchTo]) flips
 * [activeAccount], which re-points every collaborator that resolves the Active Account through
 * [AccountContext].
 *
 * Not designed for concurrent mutation in v1 — drive it from a single context, as the in-memory
 * collaborators it sits on require.
 */
interface AccountManager : AccountContext {
    /** All Accounts on this device, in the order they were added. Observable (ADR-0001). */
    val accounts: StateFlow<List<Account>>

    /**
     * Registers [account] and vaults its bearer [token] under the account's id. Re-adding an
     * existing id replaces its token and label (idempotent upsert) rather than duplicating. If no
     * Account is currently active, the newly added one becomes active — so a fresh install lands on
     * a usable Active Account without a separate switch.
     */
    suspend fun addAccount(account: Account, token: String)

    /**
     * Removes the Account with [id] and secure-wipes it: its local data ([AccountDataStore.wipe])
     * and its bearer token (SecretVault) are destroyed *before* it leaves the roster, so a crash
     * mid-removal cannot orphan secrets (ADR-0009). If it was the Active Account, active re-points
     * to another remaining Account, or `null` if none remain. A no-op if [id] is unknown.
     */
    suspend fun removeAccount(id: AccountId)

    /**
     * Makes [id] the Active Account — fast user switching (ADR-0002). Emits the new [activeAccount],
     * which re-points the repositories + secure-store reader that resolve through [AccountContext];
     * a switch touches neither tokens nor data (it need not re-authenticate). Throws
     * [IllegalArgumentException] if [id] is not a registered Account.
     */
    suspend fun switchTo(id: AccountId)
}

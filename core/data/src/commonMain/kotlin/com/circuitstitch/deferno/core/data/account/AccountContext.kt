package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of which [Account] is active right now — the seam future repositories, the
 * network client, and the secure-store token reader resolve the Active Account through, so a fast
 * user switch ([AccountManager.switchTo]) re-points them all at once (ADR-0002). A collaborator
 * that only *reads* the active Account depends on this narrow surface, not on the full
 * add/remove/switch [AccountManager] (it never mutates the boundary).
 *
 * The active Account is a process-global value shared across scenes (ADR-0008 G2);
 * [DefaultAccountManager] is its implementation.
 */
interface AccountContext {
    /**
     * The Active Account, or `null` when none is signed in. Emits on every change — first add,
     * switch, and removal of the active Account — so observers always see the current identity.
     */
    val activeAccount: StateFlow<Account?>
}

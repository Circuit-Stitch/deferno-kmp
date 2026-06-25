package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.feature.settings.AssistantEnablement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The shell's server-mediated Assistant wiring (ADR-0040, #282), pulled out of [DefaultMainShellComponent]
 * (#284): org resolution + the SINGLE per-Org availability gate + the enablement write-through. The gate
 * ([availability]) is the shared source both the Assistant Destination and the Settings [enablement] row
 * observe, so a flip from either reflects in both (no within-session divergence). The inert client
 * (non-iOS hosts / tests) leaves [availability] null, so the Assistant row stays hidden.
 */
internal class AssistantShellWiring(
    private val authRepository: AuthRepository,
    private val client: AssistantClient,
) {
    /** The active User's personal org (ADR-0040), resolved once + cached by [refresh]/[setEnabled]; null
     *  until then or when unauthenticated. The Assistant row is only shown once availability — which needs
     *  this — has resolved, so it is non-null by the time the Destination is built. */
    var orgId: OrgId? = null
        private set

    /** The SINGLE per-Org availability gate — see the class doc. `null` = loading / not applicable. */
    val availability = MutableStateFlow<AssistantAvailability?>(null)

    private suspend fun resolveOrgId(): OrgId? =
        orgId ?: (authRepository.loadMe() as? MeResult.Authenticated)?.user?.personalOrgId
            ?.also { orgId = it }

    /** (Re)fetch the gate into [availability]; the inert client (non-iOS/tests) leaves it null. */
    suspend fun refresh() {
        val org = resolveOrgId() ?: return
        (client.availability(org) as? RemoteSnapshot.Available)?.let { availability.value = it.value }
    }

    /** Flip enablement server-side; the new gate lands in [availability] (so both surfaces see it). */
    suspend fun setEnabled(enabled: Boolean): AssistantAvailability? {
        val org = resolveOrgId() ?: return null
        val updated = (client.setEnablement(org, enabled) as? RemoteSnapshot.Available)?.value
        if (updated != null) availability.value = updated
        return updated
    }

    /** The Settings Assistant-enablement seam (ADR-0040, #282): backed by the shared [availability] source +
     *  the write-through above, so the Settings row and the Destination can't diverge. */
    val enablement = object : AssistantEnablement {
        override val availability: StateFlow<AssistantAvailability?> = this@AssistantShellWiring.availability
        override suspend fun refresh() = this@AssistantShellWiring.refresh()
        override suspend fun setEnabled(enabled: Boolean) { this@AssistantShellWiring.setEnabled(enabled) }
    }
}

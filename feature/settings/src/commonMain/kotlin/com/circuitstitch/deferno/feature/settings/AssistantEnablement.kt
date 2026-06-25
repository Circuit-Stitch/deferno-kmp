package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.model.AssistantAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Settings [SettingsCategory.Assistant] enablement seam (ADR-0040, #282) — the Owner's persistent
 * disable / withdraw-consent entry point (User Story 17), mirrored from the Destination's own enable flow.
 *
 * Unlike the device-local [com.circuitstitch.deferno.core.agent.InferenceEngineCatalog] (a sync `select`),
 * Assistant enablement is a **server** call gated on the Org being `entitled`. The gate is an **observable
 * [availability] flow** — the **same shared source** the shell hands the Assistant Destination — so the two
 * surfaces can't diverge: enabling/disabling from either reflects in both. [refresh] (re)fetches it (the
 * retry seam surfaces call on open); [setEnabled] flips it server-side (the egress consent) and the result
 * lands back in [availability]. `null` means not-applicable (no Org/client, offline, or failure) → the row
 * hides. The shell backs this with the AppScope `AssistantClient` + the resolved personal-org id; non-iOS
 * hosts and tests leave it [Inert] (the row hides).
 */
interface AssistantEnablement {

    /** The shared per-Org gate, observed by every Assistant surface — `null` while loading / not applicable. */
    val availability: StateFlow<AssistantAvailability?>

    /** (Re)fetch the gate into [availability] — the retry seam the surfaces call on open. */
    suspend fun refresh()

    /** Flip enablement server-side (the egress consent on enable); the result lands in [availability]. */
    suspend fun setEnabled(enabled: Boolean)

    companion object {
        /** The inert default (non-iOS hosts, tests): the gate stays `null`, so the Settings row stays hidden. */
        val Inert: AssistantEnablement = object : AssistantEnablement {
            override val availability = MutableStateFlow<AssistantAvailability?>(null)
            override suspend fun refresh() {}
            override suspend fun setEnabled(enabled: Boolean) {}
        }
    }
}

/**
 * The Settings Destination's view of the **Assistant enablement** ([SettingsCategory.Assistant], ADR-0040).
 * [availability] is `null` while loading / when the Assistant doesn't apply. The View shows the row only when
 * [available] (the Org is `entitled`, parity with the Speech/Agent row-hiding), renders the [enabled] toggle,
 * and shows [disclosure] (the egress consent) on enable. [busy] guards the in-flight server call.
 */
data class AssistantSettings(
    val availability: AssistantAvailability? = null,
    val busy: Boolean = false,
) {
    /** The row shows only once the Org is entitled (a non-entitled / unresolved gate hides it). */
    val available: Boolean get() = availability?.entitled == true

    /** Whether the Owner has the Assistant turned on (the toggle state). */
    val enabled: Boolean get() = availability?.enabled == true

    /** The egress-consent text shown before enabling (the server's string, or the built-in fallback). */
    val disclosure: String get() = availability?.disclosureOrDefault ?: AssistantAvailability.DEFAULT_DISCLOSURE
}

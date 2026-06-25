package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.model.AssistantAvailability

/**
 * The Settings [SettingsCategory.Assistant] enablement seam (ADR-0040, #282) — the Owner's persistent
 * disable / withdraw-consent entry point (User Story 17), mirrored from the Destination's own enable flow.
 *
 * Unlike the device-local [com.circuitstitch.deferno.core.agent.InferenceEngineCatalog] (a sync `select`),
 * Assistant enablement is a **server** call gated on the Org being `entitled`: [load] fetches the current
 * [[Availability]] and [setEnabled] flips it server-side (the egress consent). Both return `null` when the
 * call can't be made (no Org/client wired, offline, or a failure) so the View keeps its prior state and
 * the row stays hidden where the Assistant doesn't apply. The shell backs this with the AppScope
 * `AssistantClient` + the resolved personal-org id; non-iOS hosts and tests leave it [Inert] (the row hides).
 */
interface AssistantEnablement {

    /** The current gate, or `null` when unavailable (not entitled / offline / no client) → the row hides. */
    suspend fun load(): AssistantAvailability?

    /** Flip enablement server-side (the egress consent on enable); the new gate, or `null` on failure. */
    suspend fun setEnabled(enabled: Boolean): AssistantAvailability?

    companion object {
        /** The inert default (non-iOS hosts, tests): every call yields `null`, so the Settings row stays hidden. */
        val Inert: AssistantEnablement = object : AssistantEnablement {
            override suspend fun load(): AssistantAvailability? = null
            override suspend fun setEnabled(enabled: Boolean): AssistantAvailability? = null
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

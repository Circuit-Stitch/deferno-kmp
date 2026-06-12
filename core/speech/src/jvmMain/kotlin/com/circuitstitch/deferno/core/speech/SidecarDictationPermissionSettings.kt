package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionPort
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionSettingsLinks

/**
 * The desktop [DictationPermissionSettings] (#120): pick the System Settings Privacy pane for whichever
 * dictation permission is actually foreclosed, **introspected live at click time** over the
 * [SidecarPermissionPort] — mic first (the headline gate), then Speech. When introspection has nothing
 * to say (no Helper, both granted — a stale denial note), it falls back to the Microphone pane rather
 * than a dead affordance; off-macOS [SidecarPermissionSettingsLinks] yields `null` and the short-circuit
 * below means the socket is never even dialed.
 */
internal class SidecarDictationPermissionSettings(
    private val permissions: SidecarPermissionPort,
    // Injected so the pane-pick logic is testable on the Linux fast path (the AC) — the real binding
    // uses the host default.
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : DictationPermissionSettings {

    override suspend fun deepLink(): String? {
        // No pane on this host at all (Linux/Windows) → nothing to introspect, don't dial.
        SidecarPermissionSettingsLinks.forCapability(SidecarPermissionCapabilities.Microphone, osName) ?: return null
        val blocked = listOf(SidecarPermissionCapabilities.Microphone, SidecarPermissionCapabilities.Speech)
            .firstOrNull { capability ->
                val status = permissions.status(capability)
                status == PermissionStatusValue.DENIED || status == PermissionStatusValue.RESTRICTED
            } ?: SidecarPermissionCapabilities.Microphone
        return SidecarPermissionSettingsLinks.forCapability(blocked, osName)
    }
}

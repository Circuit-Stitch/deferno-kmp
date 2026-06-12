package com.circuitstitch.deferno.core.speech

/**
 * Where the OS lets the person flip a foreclosed [[Dictation]] permission, if anywhere (#120). The
 * desktop New surface's "Open System Settings" affordance resolves through this seam when dictation
 * lands in `DictationStatus.PermissionPermanentlyDenied`; the JVM binding introspects the Sidecar
 * permission port live at click time and picks the blocked capability's macOS Privacy pane. Hosts
 * whose permission UX is View-owned (Android's runtime prompt + app-settings intent, iOS) bind `null`
 * — there is nothing for the shared layer to deep-link.
 *
 * Permission state itself is an [[App setting]] in CONTEXT.md terms — device-local, never persisted
 * by the client, never synced; the OS is the single source of truth and this seam only points at it.
 */
fun interface DictationPermissionSettings {

    /** The OS settings URI to open, or `null` when this host has nothing to deep-link. */
    suspend fun deepLink(): String?
}

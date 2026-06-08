package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode

/**
 * The pure write seam for the Settings categories (ADR-0001, #72) — the intents the UI drives. The
 * implementation ([OutboxSettingsWriter]) optimistically applies each change to the local cache (so
 * Appearance applies live) and enqueues the PATCH to the outbox for replay. Kept an interface so the
 * feature slice can be unit-tested against a fake on the ADR-0006 JVM-fast path.
 */
interface SettingsWriter {

    /** Set the appearance — theme family + mode (Appearance category). Applied live + persisted. */
    suspend fun setTheme(family: ThemeFamily, mode: ThemeMode)

    /** Toggle analytics/tracking (Data & Privacy category). */
    suspend fun setTracking(enabled: Boolean)

    /** Toggle the experimental drag-and-drop affordance (Task behavior category). */
    suspend fun setDragAndDrop(enabled: Boolean)

    /** Set the done-visibility windows in seconds (Task behavior category); `null` clears a window. */
    suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?)
}

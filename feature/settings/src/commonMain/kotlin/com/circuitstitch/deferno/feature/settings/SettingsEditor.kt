package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode

/**
 * The User-setting write seam the Settings Destination drives (#173) — the settings sibling of the
 * Tasks slice's `WorkingStateEditor` and the Calendar's `OccurrenceEditor`. The component calls it
 * with the backed-category intents; the shell backs it with the command executor (each intent is a
 * per-field `SettingsCommand` dispatched through the registry, ADR-0007 → optimistic local apply +
 * outbox enqueue, ADR-0001 — Appearance applies live), so the feature layer never touches the
 * registry — or the `core:data` `SettingsWriter` — directly. It suspends so the host runs each write
 * on the component's scope. Mirrors `SettingsWriter` 1:1.
 */
interface SettingsEditor {
    /** Set the appearance — theme family + mode (Appearance category). Applied live + persisted. */
    suspend fun setTheme(family: ThemeFamily, mode: ThemeMode)

    /** Toggle analytics/tracking (Data & Privacy category). */
    suspend fun setTracking(enabled: Boolean)

    /** Toggle the experimental drag-and-drop affordance (Task behavior category). */
    suspend fun setDragAndDrop(enabled: Boolean)

    /** Set the done-visibility windows in seconds (Task behavior category); `null` clears a window. */
    suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?)
}

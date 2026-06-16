package com.circuitstitch.deferno.core.data.item

import com.russhwolf.settings.Settings

/**
 * The device-local **"shake to undo"** choice (ADR-0034 decision 8, #230): whether a phone shake on the
 * Tasks tree raises the "Undo [operation]?" confirm that reverts the last Move. An **[[App setting]]** —
 * stored device-locally, **never synced** (a motion gesture is a per-device input convenience; shake is
 * never the only undo path — the snackbar and the menu remain regardless). Defaults to **on**; the confirm
 * prompt is the accidental-fire safety for reduced-motion / involuntary-movement users. Mirrors
 * [com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference].
 */
interface ShakeToUndoPreference {
    /** Whether shake-to-undo is on — defaults to `true` when none has been set. */
    fun enabled(): Boolean

    /** Persist the choice device-locally. Never synced to the backend (App setting). */
    fun setEnabled(enabled: Boolean)
}

/**
 * A non-persistent [ShakeToUndoPreference] for tests, previews, and platforms with no accelerometer path
 * yet (desktop/iOS/macOS). **Measured** (commonTest) — defaults to on and round-trips a set value.
 */
class InMemoryShakeToUndoPreference(initial: Boolean = true) : ShakeToUndoPreference {
    private var current: Boolean = initial
    override fun enabled(): Boolean = current
    override fun setEnabled(enabled: Boolean) {
        current = enabled
    }
}

/**
 * The production [ShakeToUndoPreference] over a multiplatform-settings [Settings] (#230): one commonMain
 * impl over the platform-backed store (Android SharedPreferences / desktop Preferences); each platform's
 * bindings supply the platform-backed [Settings]. Excluded from the coverage gate (a thin store adapter
 * exercised through the platform store, not the headless JVM gate, ADR-0006) — like
 * `SettingsKeepBrainDumpRecordingsPreference`.
 */
class SettingsShakeToUndoPreference(
    private val settings: Settings,
    private val default: Boolean = true,
) : ShakeToUndoPreference {
    override fun enabled(): Boolean = settings.getBoolean(KEY, default)

    override fun setEnabled(enabled: Boolean) {
        settings.putBoolean(KEY, enabled)
    }

    private companion object {
        const val KEY = "tasks.shake-to-undo"
    }
}

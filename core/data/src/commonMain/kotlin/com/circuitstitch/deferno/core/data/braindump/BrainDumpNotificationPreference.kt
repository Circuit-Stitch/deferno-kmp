package com.circuitstitch.deferno.core.data.braindump

import com.russhwolf.settings.Settings

/**
 * The device-local **"Brain dump notifications"** opt-in (#266, ADR-0037): whether a completion notification
 * fires when a Brain dump finishes (drafts ready, or a recording saved to review). An **[[App setting]]** —
 * device-local, **never synced** — like [KeepBrainDumpRecordingsPreference] it rides on. Defaults to **off**:
 * with it off the drafts simply appear in the [[Inbox]] and nothing interrupts; enabling it is the consent
 * (and, on iOS, the point at which notification authorization is requested — #271). Replaces Android's prior
 * best-effort auto-notify (which fired whenever notification permission happened to be granted).
 */
interface BrainDumpNotificationPreference {
    /** Whether a completion notification fires — defaults to `false` when none has been set. */
    fun enabled(): Boolean

    /** Persist the choice device-locally. Never synced to the backend (App setting). */
    fun setEnabled(enabled: Boolean)
}

/**
 * A non-persistent [BrainDumpNotificationPreference] for tests, previews, and targets without a real settings
 * store. **Measured** (commonTest) — defaults to off and round-trips a set value.
 */
class InMemoryBrainDumpNotificationPreference(
    initial: Boolean = false,
) : BrainDumpNotificationPreference {
    private var current: Boolean = initial
    override fun enabled(): Boolean = current
    override fun setEnabled(enabled: Boolean) {
        current = enabled
    }
}

/**
 * The production [BrainDumpNotificationPreference] over a multiplatform-settings [Settings] (#266): one
 * commonMain impl over SharedPreferences (Android) / NSUserDefaults (iOS) / `java.util.prefs` (JVM); each
 * platform's bindings supply the platform-backed [Settings]. Excluded from the coverage gate (a thin store
 * adapter exercised through the platform store, not the headless JVM gate, ADR-0006) — like
 * [SettingsKeepBrainDumpRecordingsPreference].
 */
class SettingsBrainDumpNotificationPreference(
    private val settings: Settings,
    private val default: Boolean = false,
) : BrainDumpNotificationPreference {
    override fun enabled(): Boolean = settings.getBoolean(KEY, default)

    override fun setEnabled(enabled: Boolean) {
        settings.putBoolean(KEY, enabled)
    }

    private companion object {
        const val KEY = "braindump.notifications"
    }
}

package com.circuitstitch.deferno.core.data.braindump

import com.russhwolf.settings.Settings

/**
 * The device-local monotonic counter behind a **[[Salvage draft]]**'s `Brain dump #n` title (#265): each take
 * that can't become real drafts gets the next number, so the Inbox shows `Brain dump #1`, `#2`, … in capture
 * order. An **[[App setting]]** — device-local, **never synced** — like [KeepBrainDumpRecordingsPreference] it
 * rides on: the count is per-device and meaningless on another. Survives relaunch so numbers don't reset.
 */
interface BrainDumpSalvageCounter {
    /** The next salvage number — increments and persists. The first call returns `1`. */
    fun next(): Int
}

/**
 * A non-persistent [BrainDumpSalvageCounter] for tests, previews, and targets without a real settings store.
 * **Measured** (commonTest) — counts up from [initial] (so the first `next()` returns `initial + 1`).
 */
class InMemoryBrainDumpSalvageCounter(
    initial: Int = 0,
) : BrainDumpSalvageCounter {
    private var current: Int = initial
    override fun next(): Int = ++current
}

/**
 * The production [BrainDumpSalvageCounter] over a multiplatform-settings [Settings] (#265): one commonMain impl
 * over SharedPreferences (Android) / NSUserDefaults (iOS); each platform's bindings supply the platform-backed
 * [Settings]. Excluded from the coverage gate (a thin store adapter exercised through the platform store, not
 * the headless JVM gate, ADR-0006) — like [SettingsKeepBrainDumpRecordingsPreference].
 */
class SettingsBrainDumpSalvageCounter(
    private val settings: Settings,
) : BrainDumpSalvageCounter {
    override fun next(): Int {
        val n = settings.getInt(KEY, 0) + 1
        settings.putInt(KEY, n)
        return n
    }

    private companion object {
        const val KEY = "braindump.salvage-counter"
    }
}

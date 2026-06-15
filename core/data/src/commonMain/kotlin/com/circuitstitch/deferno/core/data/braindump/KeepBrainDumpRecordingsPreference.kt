package com.circuitstitch.deferno.core.data.braindump

import com.russhwolf.settings.Settings
import kotlin.time.Instant

/**
 * The device-local **"keep brain-dump recordings"** choice (#211): whether a brain-dump's source recording
 * is retained as an on-device Task attachment (#210) when a draft is accepted in the Inbox. An
 * **[[App setting]]** — stored device-locally, **never synced** — like the storage-provider choice it rides
 * on: the audio is on-device anyway, so the preference lives where the bytes do. Defaults to **on** (the
 * recording is the user's own capture; keeping it lets them revisit the source of a draft). Mirrors
 * [com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference].
 */
interface KeepBrainDumpRecordingsPreference {
    /** Whether new brain-dump recordings are retained — defaults to `true` when none has been set. */
    fun enabled(): Boolean

    /** Persist the choice device-locally. Never synced to the backend (App setting). */
    fun setEnabled(enabled: Boolean)
}

/**
 * A non-persistent [KeepBrainDumpRecordingsPreference] for tests, previews, and the targets that don't
 * capture brain dumps yet (desktop/iOS). **Measured** (commonTest) — defaults to on and round-trips a set value.
 */
class InMemoryKeepBrainDumpRecordingsPreference(
    initial: Boolean = true,
) : KeepBrainDumpRecordingsPreference {
    private var current: Boolean = initial
    override fun enabled(): Boolean = current
    override fun setEnabled(enabled: Boolean) {
        current = enabled
    }
}

/**
 * The production [KeepBrainDumpRecordingsPreference] over a multiplatform-settings [Settings] (#211): one
 * commonMain impl over SharedPreferences (Android) — the only target that captures brain dumps; each
 * platform's bindings supply the platform-backed [Settings]. Excluded from the coverage gate (a thin store
 * adapter exercised through the platform store, not the headless JVM gate, ADR-0006) — like
 * `SettingsStorageProviderPreference`.
 */
class SettingsKeepBrainDumpRecordingsPreference(
    private val settings: Settings,
    private val default: Boolean = true,
) : KeepBrainDumpRecordingsPreference {
    override fun enabled(): Boolean = settings.getBoolean(KEY, default)

    override fun setEnabled(enabled: Boolean) {
        settings.putBoolean(KEY, enabled)
    }

    private companion object {
        const val KEY = "braindump.keep-recordings"
    }
}

/**
 * The on-device locator the retained brain-dump recording is stored under (#211): a flat, recording-keyed id
 * in the shared attachment byte store, distinct from the per-Task attachment ids (a server-assigned uuid).
 * Keyed by the recording's [createdAt] (the worker stamps one instant per recording → all its drafts share
 * it), so the worker that retains the WAV and the Inbox accept that attaches it agree without a DB column.
 */
fun brainDumpRecordingPlaceholderId(createdAt: Instant): String =
    "braindump-audio-${createdAt.toEpochMilliseconds()}"

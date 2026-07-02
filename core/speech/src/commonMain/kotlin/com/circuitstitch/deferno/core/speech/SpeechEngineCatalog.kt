package com.circuitstitch.deferno.core.speech

/**
 * One selectable choice the Settings "Speech engine" row offers (#93, ADR-0018): an engine [id] plus its
 * current [availability] for the device locale. The [SpeechEngineId.Automatic] option always carries
 * [SpeechAvailability.Available] (it is a strategy, not an engine — "use whatever is available"); a real
 * engine carries its genuine on-device readiness so the View can show *why* it isn't usable yet (model
 * still arriving, English-only, …) without ever implying a cloud path (structural never-cloud, ADR-0018).
 */
data class SpeechEngineOption(
    val id: SpeechEngineId,
    val availability: SpeechAvailability,
)

/**
 * The device-local **speech-engine choice** read model surfaced to the Settings Destination (#93,
 * ADR-0018): a thin view over the registered engines (the `Set<SpeechToText>` multibinding) plus the
 * device-local [SpeechEnginePreference]. It lists the engines available **on this device** (plus the
 * [SpeechEngineId.Automatic] rank-pick), reports the current choice, and persists a new one.
 *
 * It is an **[[App setting]]**: device-local, **never synced**, never crossing Accounts — the same engine
 * may not exist on another device (it is bound at AppScope, like the selector, not AccountScope). The
 * Settings component reads it to render the row and bind selection; the [SpeechToTextSelector] honours the
 * same underlying [SpeechEnginePreference] when it picks an engine per `listen()`.
 */
interface SpeechEngineCatalog {
    /**
     * The selectable options for [locale]: [SpeechEngineId.Automatic] first, then each **real** registered
     * engine in descending [SpeechToText.rank] (the order the selector prefers them), each with its current
     * [SpeechAvailability]. The always-unavailable [UnavailableSpeechToText] floor is **excluded** — it is
     * not a user choice — so a device with no real engine yet (desktop/iOS pre-#94/#95) yields only the
     * Automatic strategy, and the Settings View hides the row in that case (#93).
     */
    suspend fun options(locale: String): List<SpeechEngineOption>

    /** The current device-local choice — defaults to [SpeechEngineId.Automatic] when none is set (ADR-0018). */
    fun selected(): SpeechEngineId

    /** Persist the device-local choice. **Never** synced to the backend (App setting, ADR-0018). */
    fun select(id: SpeechEngineId)
}

/**
 * The production [SpeechEngineCatalog] over the registered [engines] + the device-local [preference]
 * (#93). [SpeechEngineId.Automatic] always leads the option list; the real engines follow in descending
 * [SpeechToText.rank], each carrying its live [availability] for the queried locale. The
 * [UnavailableSpeechToText] floor is filtered out (it is a fallback, never a user-facing choice).
 *
 * It is **measured** (commonTest) — the enumeration, ordering, floor-exclusion, and the preference
 * round-trip are real logic; only the `multiplatform-settings`-backed [SettingsSpeechEnginePreference] it
 * is composed over on a real device is coverage-excluded (ADR-0006).
 */
class DefaultSpeechEngineCatalog(
    private val engines: Set<SpeechToText>,
    private val preference: SpeechEnginePreference,
) : SpeechEngineCatalog {
    override suspend fun options(locale: String): List<SpeechEngineOption> {
        val real = engines
            .filterNot { it.id == SpeechEngineId.Unavailable }
            .sortedByDescending { it.rank }
            .map { SpeechEngineOption(it.id, it.availability(locale)) }
        return listOf(SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available)) + real
    }

    override fun selected(): SpeechEngineId = preference.preferredEngine()

    override fun select(id: SpeechEngineId) = preference.setPreferredEngine(id)
}

/**
 * The inert, empty [SpeechEngineCatalog]: no real engine, so [options] is just the
 * [SpeechEngineId.Automatic] strategy (the Settings row hides) and [select] is a no-op. The analogue of
 * [UnavailableSpeechToText] — a safe, stateless default for a host or test with no speech graph wired
 * (the shell's many tests, desktop before its engine lands in #94). Measured (commonTest): deterministic,
 * inert behaviour worth pinning, like the [UnavailableSpeechToText] floor.
 */
object EmptySpeechEngineCatalog : SpeechEngineCatalog {
    override suspend fun options(locale: String): List<SpeechEngineOption> =
        listOf(SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available))

    override fun selected(): SpeechEngineId = SpeechEngineId.Automatic

    override fun select(id: SpeechEngineId) { /* inert: no device-local store without a real engine */ }
}

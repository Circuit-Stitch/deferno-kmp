package com.circuitstitch.deferno.feature.settings

import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [SettingsRepository] for the [SettingsComponent] tests (#72). Backed by a
 * [MutableStateFlow] so [observeSettings] is a real, re-emitting `Flow`; [refresh] is a no-op (the
 * data is already local). Mirrors the demo repository fakes.
 */
class FakeSettingsRepository(initial: UserSettings = UserSettings.Default) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override fun observeSettings(): Flow<UserSettings> = state
    override suspend fun refresh() { /* offline fake: nothing to pull */ }
}

/**
 * In-memory [SettingsEditor] for the [SettingsComponent] tests (#72/#173). Records each intent and
 * mirrors the optimistic apply into [repo] so the component's observed [SettingsComponent.settings]
 * reflects the write (as the real command-backed editor does through `OutboxSettingsWriter`).
 */
class FakeSettingsEditor(private val repo: FakeSettingsRepository) : SettingsEditor {
    var themeChanges = mutableListOf<Pair<ThemeFamily, ThemeMode>>()
        private set
    var trackingChanges = mutableListOf<Boolean>()
        private set
    var dragAndDropChanges = mutableListOf<Boolean>()
        private set
    var doneVisibilityChanges = mutableListOf<Pair<Long?, Long?>>()
        private set

    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) {
        themeChanges += family to mode
        repo.state.value = repo.state.value.copy(themeFamily = family, themeMode = mode)
    }

    override suspend fun setTracking(enabled: Boolean) {
        trackingChanges += enabled
        repo.state.value = repo.state.value.copy(trackingEnabled = enabled)
    }

    override suspend fun setDragAndDrop(enabled: Boolean) {
        dragAndDropChanges += enabled
        repo.state.value = repo.state.value.copy(dragAndDropEnabled = enabled)
    }

    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) {
        doneVisibilityChanges += globalSeconds to dashboardSeconds
        repo.state.value = repo.state.value.copy(
            globalDoneVisibilitySeconds = globalSeconds,
            dashboardDoneVisibilitySeconds = dashboardSeconds,
        )
    }
}

/**
 * In-memory [SpeechEngineCatalog] for the [SettingsComponent] speech-engine tests (#93). Returns a fixed
 * [fixedOptions] list and records each [select] so the test can assert the device-local choice was
 * persisted through the catalog (the analogue of [FakeSettingsEditor] for the App setting).
 */
class FakeSpeechEngineCatalog(
    private val fixedOptions: List<SpeechEngineOption> = emptyList(),
    initial: SpeechEngineId = SpeechEngineId.Whisper,
) : SpeechEngineCatalog {
    var current: SpeechEngineId = initial
        private set
    val selects = mutableListOf<SpeechEngineId>()

    override suspend fun options(locale: String): List<SpeechEngineOption> = fixedOptions
    override fun selected(): SpeechEngineId = current
    override fun select(id: SpeechEngineId) {
        selects += id
        current = id
    }
}

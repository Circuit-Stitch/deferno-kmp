package com.circuitstitch.deferno.feature.settings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop Settings render test (#85, cf. #39) — a Compose-Multiplatform UI test on the JVM-fast path
 * (no device) over a real [DefaultSettingsComponent] + in-memory settings fakes. It covers the category
 * list (the coming-soon rows present, the App Permissions row omitted on desktop), the Appearance detail
 * (choosing a theme family persists through the editor seam, #173 — the live re-theme signal), and a
 * coming-soon stub detail. The drill-down/back logic itself is unit-tested in feature:settings.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsDesktopScreenTest {

    private fun component(
        repo: FakeSettingsRepository,
        editor: FakeSettingsEditor,
        category: SettingsCategory? = null,
        speechEngineCatalog: SpeechEngineCatalog = FakeSpeechEngineCatalog(),
    ): SettingsComponent = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = repo,
        settingsEditor = editor,
        speechEngineCatalog = speechEngineCatalog,
        coroutineContext = Dispatchers.Unconfined,
    ).also { if (category != null) it.openCategory(category) }

    @Test
    fun categoryList_showsComingSoonRows_omitsAppPermissions() = runComposeUiTest {
        val repo = FakeSettingsRepository()
        setContent { Themed { SettingsDesktopScreen(component(repo, FakeSettingsEditor(repo))) } }

        onNodeWithText("Appearance").assertExists()
        // The two unbacked categories render as coming-soon rows (no dead ends, ADR-0015).
        onNodeWithText("Integrations").assertExists()
        onNodeWithText("Security & 2FA").assertExists()
        // App Permissions is omitted on desktop — there is no per-app OS settings screen (ADR-0017).
        onNodeWithText("App Permissions").assertDoesNotExist()
        // The device-local Speech engine row is hidden where no engine is registered (#93) — desktop has
        // none until #94, so the shared screen shows nothing rather than a dead "Automatic"-only row.
        onNodeWithText("Speech engine").assertDoesNotExist()
    }

    @Test
    fun speechEngineRow_shows_andSelecting_persists_whenADesktopEngineIsPresent() = runComposeUiTest {
        // Forward-compat for the desktop engine (#94): once one is registered the shared screen renders the
        // device-local Speech engine row + detail (the same path Android takes), and selecting persists it.
        val repo = FakeSettingsRepository()
        val catalog = FakeSpeechEngineCatalog(
            fixedOptions = listOf(
                SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available),
                SpeechEngineOption(SpeechEngineId.Whisper, SpeechAvailability.Available),
            ),
        )
        setContent {
            Themed { SettingsDesktopScreen(component(repo, FakeSettingsEditor(repo), speechEngineCatalog = catalog)) }
        }

        onNodeWithText("Speech engine").performClick()
        onNodeWithText("Automatic").performClick()

        assertEquals(listOf(SpeechEngineId.Automatic), catalog.selects)
    }

    @Test
    fun appearanceDetail_choosingMono_persistsThroughEditor() = runComposeUiTest {
        val repo = FakeSettingsRepository()
        val editor = FakeSettingsEditor(repo)
        setContent { Themed { SettingsDesktopScreen(component(repo, editor, SettingsCategory.Appearance)) } }

        onNodeWithText("Theme").assertExists()
        onNodeWithText("Mono").performClick()
        // The live re-theme signal: the same UserSettings flow drives the desktop window's theme.
        assertTrue(editor.themeChanges.any { it.first == ThemeFamily.Mono })
    }

    @Test
    fun integrationsDetail_showsComingSoonStub() = runComposeUiTest {
        val repo = FakeSettingsRepository()
        setContent {
            Themed { SettingsDesktopScreen(component(repo, FakeSettingsEditor(repo), SettingsCategory.Integrations)) }
        }
        onNodeWithText("Coming soon").assertExists()
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** In-memory [SettingsRepository] backed by a re-emitting [MutableStateFlow] (mirrors feature:settings). */
private class FakeSettingsRepository(initial: UserSettings = UserSettings.Default) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override fun observeSettings(): Flow<UserSettings> = state
    override suspend fun refresh() { /* offline fake: nothing to pull */ }
}

/** In-memory [SettingsEditor] recording each theme write + mirroring the optimistic apply into [repo]. */
private class FakeSettingsEditor(private val repo: FakeSettingsRepository) : SettingsEditor {
    val themeChanges = mutableListOf<Pair<ThemeFamily, ThemeMode>>()

    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) {
        themeChanges += family to mode
        repo.state.value = repo.state.value.copy(themeFamily = family, themeMode = mode)
    }

    override suspend fun setTracking(enabled: Boolean) = Unit
    override suspend fun setDragAndDrop(enabled: Boolean) = Unit
    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) = Unit
}

/** In-memory [SpeechEngineCatalog] recording each [select] (#93); empty options → the row hides. */
private class FakeSpeechEngineCatalog(
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

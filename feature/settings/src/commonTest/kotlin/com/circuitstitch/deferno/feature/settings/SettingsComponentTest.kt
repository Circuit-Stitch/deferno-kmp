package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.core.speech.UnavailableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DefaultSettingsComponent] (#72, ADR-0007 tier 3): the tier-3 drill-down — opening a category pushes
 * its detail child and [onBack] pops cleanly back to the list; the backed-category intents call the
 * narrow [SettingsEditor] seam (#173 — never `SettingsWriter` directly; the change is reflected in the
 * observed [SettingsComponent.settings] StateFlow — the live-apply round-trip); the coming-soon
 * categories open a stub detail (no crash, no dead tap); and the host-routed intents (App Permissions /
 * Security 2FA / Account-Profile) emit the right [Output]. Driven on [Dispatchers.Unconfined] so the
 * editor's optimistic apply is observable synchronously.
 */
class SettingsComponentTest {

    private fun component(
        initial: UserSettings = UserSettings.Default,
        output: (SettingsComponent.Output) -> Unit = {},
        speechEngineCatalog: SpeechEngineCatalog = FakeSpeechEngineCatalog(),
    ): Triple<DefaultSettingsComponent, FakeSettingsRepository, FakeSettingsEditor> {
        val repo = FakeSettingsRepository(initial)
        val editor = FakeSettingsEditor(repo)
        val component = DefaultSettingsComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            settingsRepository = repo,
            settingsEditor = editor,
            output = output,
            speechEngineCatalog = speechEngineCatalog,
            coroutineContext = Dispatchers.Unconfined,
        )
        return Triple(component, repo, editor)
    }

    /** A catalog offering Automatic + an available Whisper — the Android v1 shape (a real engine present). */
    private fun whisperCatalog() = FakeSpeechEngineCatalog(
        fixedOptions = listOf(
            SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available),
            SpeechEngineOption(SpeechEngineId.Whisper, SpeechAvailability.Available),
        ),
        initial = SpeechEngineId.Whisper,
    )

    @Test
    fun opensAtTheCategoryList() {
        val (component, _, _) = component()
        assertEquals(SettingsComponent.SettingsChild.List, component.stack.value.active.instance)
    }

    @Test
    fun openingACategoryPushesItsDetail_andBackPopsToTheList() {
        val (component, _, _) = component()

        component.openCategory(SettingsCategory.Appearance)
        val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
        assertEquals(SettingsCategory.Appearance, detail.category)

        assertTrue(component.onBack(), "back pops the category detail")
        assertEquals(SettingsComponent.SettingsChild.List, component.stack.value.active.instance)

        assertFalse(component.onBack(), "back at the list root is not consumed")
    }

    @Test
    fun appearanceFamilyAndMode_callTheEditor_andReflectInSettings() {
        val (component, _, editor) = component(initial = UserSettings.Default)

        component.onThemeFamilyChanged(ThemeFamily.Mono)
        assertEquals(listOf(ThemeFamily.Mono to ThemeMode.Auto), editor.themeChanges)
        assertEquals(ThemeFamily.Mono, component.settings.value.themeFamily)

        component.onThemeModeChanged(ThemeMode.Dark)
        // The second change carries the already-applied family — independent fields don't clobber.
        assertEquals(ThemeFamily.Mono to ThemeMode.Dark, editor.themeChanges.last())
        assertEquals(ThemeMode.Dark, component.settings.value.themeMode)
    }

    @Test
    fun taskBehaviorAndDataPrivacyToggles_callTheEditor() {
        val (component, _, editor) = component()

        component.onDragAndDropChanged(true)
        component.onTrackingChanged(true)
        component.onDoneVisibilityChanged(259200L, 86400L)

        assertEquals(listOf(true), editor.dragAndDropChanges)
        assertEquals(listOf(true), editor.trackingChanges)
        assertEquals(listOf<Pair<Long?, Long?>>(259200L to 86400L), editor.doneVisibilityChanges)
        assertEquals(true, component.settings.value.dragAndDropEnabled)
        assertEquals(true, component.settings.value.trackingEnabled)
    }

    @Test
    fun comingSoonCategoriesOpenAStubDetail_noCrashNoDeadTap() {
        for (category in listOf(SettingsCategory.Security2FA, SettingsCategory.Integrations)) {
            val (component, _, _) = component()
            assertFalse(category.backed, "the unbacked category is a stub")

            component.openCategory(category)

            val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
            assertEquals(category, detail.category)
            // It is a real detail child (not a dead tap) and backs out cleanly.
            assertTrue(component.onBack())
        }
    }

    @Test
    fun appPermissions_emitsOpenOsAppSettings() {
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenAppPermissions()

        assertEquals(listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenOsAppSettings), outputs)
    }

    @Test
    fun dataPrivacy_exportImport_emitsOpenDataExportImport() {
        // Export/import has no client REST endpoint at envelope v0.1, but it must be a REACHABLE web
        // action (AC #3) — not dead prose: tapping it asks the host to deep-link the web app.
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenDataExportImport()

        assertEquals(
            listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenDataExportImport),
            outputs,
        )
    }

    @Test
    fun helpFeedback_submit_emitsOpenSubmitFeedback() {
        // Submit-feedback is reachable, not static text (AC #4): the tap asks the shell to open the
        // in-app Feedback overlay (#375).
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenSubmitFeedback()

        assertEquals(
            listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenSubmitFeedback),
            outputs,
        )
    }

    @Test
    fun security2FA_emitsOpenConsoleUrl_andAccount_emitsOpenProfile() {
        val outputs = mutableListOf<SettingsComponent.Output>()
        val (component, _, _) = component(output = outputs::add)

        component.onOpenConsole()
        component.onOpenProfile()

        assertEquals(
            listOf<SettingsComponent.Output>(
                SettingsComponent.Output.OpenConsoleUrl,
                SettingsComponent.Output.OpenProfile,
            ),
            outputs,
        )
    }

    @Test
    fun everyWireframeCategoryIsListed_backedAndUnbacked() {
        // The catalog must render ALL categories (#72): eight backed (incl. the device-local Speech
        // engine, #93) + two coming-soon stubs.
        assertEquals(8, SettingsCategory.entries.count { it.backed })
        assertEquals(
            listOf(SettingsCategory.Security2FA, SettingsCategory.Integrations),
            SettingsCategory.entries.filterNot { it.backed },
        )
    }

    // --- Speech engine: the device-local App setting (#93, ADR-0018) ---

    @Test
    fun speechEngine_exposesCatalogOptions_andDefaultsToWhisper() {
        val (component, _, _) = component(speechEngineCatalog = whisperCatalog())

        val state = component.speechEngine.value
        assertEquals(listOf(SpeechEngineId.Automatic, SpeechEngineId.Whisper), state.options.map { it.id })
        assertEquals(SpeechEngineId.Whisper, state.selected)
        assertTrue(state.available, "a real engine (whisper) is registered, so the row shows")
    }

    @Test
    fun selectingASpeechEngine_persistsThroughCatalog_andReflectsImmediately() {
        val catalog = whisperCatalog()
        val (component, _, _) = component(speechEngineCatalog = catalog)

        component.onSpeechEngineSelected(SpeechEngineId.Automatic)

        // Persisted device-locally through the catalog (App setting) — NOT the synced SettingsEditor.
        assertEquals(listOf(SpeechEngineId.Automatic), catalog.selects)
        assertEquals(SpeechEngineId.Automatic, catalog.current)
        assertEquals(SpeechEngineId.Automatic, component.speechEngine.value.selected)
    }

    @Test
    fun speechEngineRow_isHidden_whenNoRealEngineOnThisDevice() {
        // Desktop/iOS pre-engine (#94/#95): the catalog yields only the Automatic strategy.
        val onlyAutomatic = FakeSpeechEngineCatalog(
            fixedOptions = listOf(SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available)),
        )
        val (component, _, _) = component(speechEngineCatalog = onlyAutomatic)

        assertFalse(component.speechEngine.value.available, "only Automatic → no real engine → row hidden")
    }

    @Test
    fun selectingAnUnavailableEngine_stillRecordsThePreference() {
        // AC3: a chosen-but-unavailable engine still records the preference (the selector falls back to the
        // whisper floor at listen() time, never cloud), so the choice is honoured once it becomes available.
        val nativeId = SpeechEngineId("native-fast-path")
        val catalog = FakeSpeechEngineCatalog(
            fixedOptions = listOf(
                SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available),
                SpeechEngineOption(SpeechEngineId.Whisper, SpeechAvailability.Available),
                SpeechEngineOption(nativeId, SpeechAvailability.Unavailable(UnavailableReason.ModelMissing)),
            ),
        )
        val (component, _, _) = component(speechEngineCatalog = catalog)

        component.onSpeechEngineSelected(nativeId)

        assertEquals(listOf(nativeId), catalog.selects)
        assertEquals(nativeId, component.speechEngine.value.selected)
    }

    @Test
    fun speechEngineCategory_drillsDownAndBacksOut_likeAnyCategory() {
        val (component, _, _) = component(speechEngineCatalog = whisperCatalog())

        component.openCategory(SettingsCategory.SpeechEngine)
        val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
        assertEquals(SettingsCategory.SpeechEngine, detail.category)

        assertTrue(component.onBack(), "back pops the Speech engine detail to the list")
    }
}

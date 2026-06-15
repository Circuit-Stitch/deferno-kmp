package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.agent.FakeRelayEntitlement
import com.circuitstitch.deferno.core.agent.InMemoryInferenceEnginePreference
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.core.speech.UnavailableReason
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests (#72) for the Settings tier-3 drill-down, run on the JVM via
 * Robolectric. They guard the View wiring the component test can't: tapping a category row drills
 * into its detail (and the back arrow returns to the list), an Appearance theme choice forwards the
 * intent to the editor seam (#173), and the drag-and-drop toggle forwards its intent. Driven over the
 * real [DefaultSettingsComponent] on top of the in-memory settings fakes (the same harness pattern as
 * the Profile interaction tests).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SettingsScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun component(
        repo: FakeSettingsRepository = FakeSettingsRepository(),
        editor: FakeSettingsEditor = FakeSettingsEditor(repo),
        speechEngineCatalog: SpeechEngineCatalog = FakeSpeechEngineCatalog(),
        inferenceEngineCatalog: InferenceEngineCatalog = InferenceEngineCatalog.Inert,
    ) = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = repo,
        settingsEditor = editor,
        speechEngineCatalog = speechEngineCatalog,
        inferenceEngineCatalog = inferenceEngineCatalog,
        coroutineContext = Dispatchers.Unconfined,
    )

    /** A catalog with the cloud relay engine present (Agent row available) + flippable entitlement. */
    private fun agentCatalog(
        selected: InferenceEngineId = InferenceEngineId.Off,
        entitled: Boolean = false,
    ) = InferenceEngineCatalog.forRelay(
        baseUrl = "https://relay/",
        preference = InMemoryInferenceEnginePreference(selected),
        entitlement = FakeRelayEntitlement(entitled),
    )

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun listShowsEveryCategory_includingTheComingSoonStubs() {
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Task behavior").assertIsDisplayed()
        composeRule.onNodeWithText("Data & Privacy").assertIsDisplayed()
        composeRule.onNodeWithText("Security & 2FA").assertIsDisplayed()
        composeRule.onNodeWithText("Integrations").assertIsDisplayed()
    }

    @Test
    fun tappingACategoryRow_drillsIntoTheDetail_andBackReturnsToTheList() {
        val component = component()
        setContent { SettingsScreen(component) }

        composeRule.onNodeWithText("Task behavior").performClick()
        // The detail screen shows the drag-and-drop toggle.
        composeRule.onNodeWithText("Drag and drop (experimental)").assertIsDisplayed()

        // Back now lives in the shell's single top bar (Cand 1) — outside this isolated screen — so drive
        // the same SettingsComponent.onBack() its ← invokes; the drill-down + pop is the component's own.
        composeRule.runOnIdle { component.onBack() }
        // Back at the list — another category row is visible again.
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun appearance_selectingMono_forwardsTheThemeIntent() {
        val repo = FakeSettingsRepository()
        val editor = FakeSettingsEditor(repo)
        setContent { SettingsScreen(component(repo, editor)) }

        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.onNodeWithText("Mono").performClick()

        assertTrue("a theme write was forwarded", editor.themeChanges.isNotEmpty())
        assertEquals(ThemeFamily.Mono, editor.themeChanges.last().first)
    }

    @Test
    fun taskBehavior_togglingDragAndDrop_forwardsTheIntent() {
        val repo = FakeSettingsRepository()
        val editor = FakeSettingsEditor(repo)
        setContent { SettingsScreen(component(repo, editor)) }

        composeRule.onNodeWithText("Task behavior").performClick()
        composeRule.onNodeWithText("Drag and drop (experimental)").performClick()

        assertEquals(listOf(true), editor.dragAndDropChanges)
    }

    @Test
    fun dataPrivacy_exportImportButton_forwardsTheReachableWebAction() {
        // AC #3: export/import must be a REACHABLE action (a real button → Output), not static prose.
        val outputs = mutableListOf<SettingsComponent.Output>()
        val repo = FakeSettingsRepository()
        setContent {
            SettingsScreen(
                DefaultSettingsComponent(
                    componentContext = DefaultComponentContext(LifecycleRegistry()),
                    settingsRepository = repo,
                    settingsEditor = FakeSettingsEditor(repo),
                    output = outputs::add,
                    coroutineContext = Dispatchers.Unconfined,
                ),
            )
        }

        composeRule.onNodeWithText("Data & Privacy").performClick()
        composeRule.onNodeWithText("Export or import your data").performClick()

        assertEquals(
            listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenDataExportImport),
            outputs,
        )
    }

    @Test
    fun helpFeedback_submitButton_forwardsTheReachableWebAction() {
        // AC #4: submit-feedback must be a REACHABLE action (a real button → Output), not static prose.
        val outputs = mutableListOf<SettingsComponent.Output>()
        val repo = FakeSettingsRepository()
        setContent {
            SettingsScreen(
                DefaultSettingsComponent(
                    componentContext = DefaultComponentContext(LifecycleRegistry()),
                    settingsRepository = repo,
                    settingsEditor = FakeSettingsEditor(repo),
                    output = outputs::add,
                    coroutineContext = Dispatchers.Unconfined,
                ),
            )
        }

        composeRule.onNodeWithText("Help & Feedback").performClick()
        composeRule.onNodeWithText("Send feedback").performClick()

        assertEquals(
            listOf<SettingsComponent.Output>(SettingsComponent.Output.OpenSubmitFeedback),
            outputs,
        )
    }

    @Test
    fun comingSoonStub_rendersGently_withNoDeadTap() {
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Integrations").performClick()
        // It opens a real detail explaining the coming-soon state (not a dead tap).
        composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
    }

    // --- Speech engine: the device-local App setting (#93, ADR-0018) ---

    @Test
    fun speechEngineRow_isHidden_whenThisDeviceHasNoRealEngine() {
        // The default catalog yields only "Automatic" (no real engine) — the desktop/iOS pre-engine case.
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Speech engine").assertDoesNotExist()
    }

    @Test
    fun speechEngineRow_shows_andSelectingAutomatic_persistsTheDeviceLocalChoice() {
        val catalog = FakeSpeechEngineCatalog.whisper()
        setContent { SettingsScreen(component(speechEngineCatalog = catalog)) }

        // The row shows on a device with a real engine (Android, whisper), summarised by the current choice.
        composeRule.onNodeWithText("Speech engine").performClick()
        // The detail lists Automatic + Whisper; choose Automatic.
        composeRule.onNodeWithText("Whisper").assertIsDisplayed()
        composeRule.onNodeWithText("Automatic").performClick()

        // Persisted device-locally through the catalog — never the synced SettingsEditor.
        assertEquals(listOf(SpeechEngineId.Automatic), catalog.selects)
    }

    @Test
    fun speechEngineRow_reflectsUnavailability_inSummaryAndDetailNote() {
        // AC3 "the row reflects unavailability": Whisper is registered but its model is still arriving.
        val catalog = FakeSpeechEngineCatalog(
            fixedOptions = listOf(
                SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available),
                SpeechEngineOption(SpeechEngineId.Whisper, SpeechAvailability.Unavailable(UnavailableReason.ModelMissing)),
            ),
            initial = SpeechEngineId.Whisper,
        )
        setContent { SettingsScreen(component(speechEngineCatalog = catalog)) }

        // The list row summary flags the chosen-but-unavailable engine.
        composeRule.onNodeWithText("Whisper · unavailable").assertIsDisplayed()

        // Drilling in, the per-engine note explains WHY (the ModelMissing → "Downloading…" mapping).
        composeRule.onNodeWithText("Speech engine").performClick()
        composeRule.onNodeWithText("Downloading…").assertIsDisplayed()
    }

    // --- Agent: the device-local opt-in App setting + entitlement gate (#150, ADR-0027) ---

    @Test
    fun agentRow_isHidden_whenThisDeviceHasNoEngine() {
        // The inert default gate has no engine → the Agent row never shows (like the speech row).
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Agent").assertDoesNotExist()
    }

    @Test
    fun agent_entitled_showsConsentCopy_andSelectingCloudPersistsDeviceLocally() {
        // AC1: the consent copy is shown; selecting the cloud engine is the opt-in and persists through the
        // catalog (device-local), never the synced SettingsEditor.
        val catalog = agentCatalog(selected = InferenceEngineId.Off, entitled = true)
        val editor = FakeSettingsEditor(FakeSettingsRepository())
        setContent { SettingsScreen(component(editor = editor, inferenceEngineCatalog = catalog)) }

        composeRule.onNodeWithText("Agent").performClick()
        composeRule.onNodeWithText("Deferno cloud AI").assertIsDisplayed()
        // The consent copy names the off-device send before the cloud engine is chosen.
        composeRule.onNodeWithText("off your device", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Deferno cloud AI").performClick()

        assertEquals(InferenceEngineId.DefernoCloud, catalog.selected())
        assertTrue("the selection never touched the synced settings editor", editor.trackingChanges.isEmpty())
    }

    @Test
    fun agent_notEntitled_rendersADisabledCloudOption_thatCannotBeSelected() {
        // AC2: a not-entitled Account sees the cloud engine disabled "Premium" — it can't be selected, so no
        // inference is ever attempted; the selection stays Off.
        val catalog = agentCatalog(selected = InferenceEngineId.Off, entitled = false)
        setContent { SettingsScreen(component(inferenceEngineCatalog = catalog)) }

        composeRule.onNodeWithText("Agent").performClick()
        composeRule.onNodeWithText("Premium — not available for your account yet").assertIsDisplayed()

        // The cloud option is a disabled, unselected radio — it cannot be chosen, so no inference is attempted.
        composeRule.onNodeWithText("Deferno cloud AI").assertIsNotEnabled()
        assertEquals(InferenceEngineId.Off, catalog.selected())
    }
}

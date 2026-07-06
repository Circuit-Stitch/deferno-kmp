package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.agent.FakeRelayEntitlement
import com.circuitstitch.deferno.core.agent.InMemoryInferenceEnginePreference
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.security.SecurityResult
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.TotpEnrollment
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.core.speech.UnavailableReason
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant
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
        security: SecurityRepository = SecurityRepository.Inert,
        activeTokenId: String? = null,
    ) = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = repo,
        settingsEditor = editor,
        speechEngineCatalog = speechEngineCatalog,
        inferenceEngineCatalog = inferenceEngineCatalog,
        securityRepository = security,
        activeTokenId = activeTokenId,
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
        // Security & 2FA is a real screen on Android now — only Integrations still reads Coming soon.
        composeRule.onAllNodesWithText("Coming soon").assertCountEquals(1)
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
    fun dataPrivacy_exportMenu_offersSaveAndDisablesFullBackup() {
        // #313: the Android export is in-app now. "Export your data" opens a menu offering the wired
        // on-device Export (saves the Backup zip via the SAF "Save to…" picker) + a disabled "Full
        // backup — coming soon" teaser mirroring the iOS action sheet. The actual save runs through the
        // system document picker, which can't be driven here — the build path is covered in core:data.
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Data & Privacy").performClick()
        composeRule.onNodeWithText("Export your data").performClick()
        composeRule.onNodeWithText("Export").assertIsEnabled()
        composeRule.onNodeWithText("Full backup — coming soon").assertIsNotEnabled()
    }

    @Test
    fun dataPrivacy_offersImportABackup() {
        // #314: the in-app import (restore) action. The actual pick+restore runs through the system
        // document picker (OpenDocument) — not drivable here, like export — so this only guards that the
        // reachable Import button renders; the restore engine + result mapping are covered in core:data.
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Data & Privacy").performClick()
        composeRule.onNodeWithText("Import a backup").assertIsDisplayed()
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
        composeRule.onNodeWithText("Whisper (on-device)").assertIsDisplayed()
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
        composeRule.onNodeWithText("Whisper (on-device) · unavailable").assertIsDisplayed()

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

    // --- Security & 2FA (the real screen, #72 follow-through) ---

    @Test
    fun security_unwiredHost_rendersUnavailableWithRetry_neverADeadTap() {
        // The component's default seam is SecurityRepository.Inert — the unwired-host case.
        setContent { SettingsScreen(component()) }

        composeRule.onNodeWithText("Security & 2FA").performClick()

        // The copy appears once per section (the 2FA summary AND the devices list are both unavailable).
        composeRule.onAllNodesWithText("Security settings couldn't be loaded", substring = true)[0].assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun security_enrolledSummary_offersBackupReplaceAndTurnOff_andDisableAsksFirst() {
        val security = ScriptedSecurityRepository(
            status = SecurityResult.Success(MfaStatus(totpEnabled = true, emailBackup = false)),
        )
        setContent { SettingsScreen(component(security = security)) }

        composeRule.onNodeWithText("Security & 2FA").performClick()
        composeRule.onNodeWithText("Authenticator app is on").assertIsDisplayed()
        composeRule.onNodeWithText("Add email backup").assertIsDisplayed()

        // Turn off is destructive: it must confirm BEFORE dispatching anything.
        composeRule.onNodeWithText("Turn off two-factor authentication").performClick()
        composeRule.onNodeWithText("Turn off two-factor authentication?").assertIsDisplayed()
        assertEquals(0, security.disableCalls)

        composeRule.onNodeWithText("Turn off").performClick()
        assertEquals(1, security.disableCalls)
    }

    // NOT UI-tested here: the enroll / step-up / recovery-codes dialog flows. A Compose dialog whose
    // content holds a text field never reports idle under Robolectric (the field's cursor-blink is
    // an infinite animation the RobolectricIdlingStrategy keeps waiting on — mainClock.autoAdvance
    // doesn't bypass it), so every sync after such a dialog opens dies in AppNotIdleException.
    // The full step machine — enroll → code entry → recovery codes → enrolled, step-up interrupt +
    // exact-pending-action resume, wrong code/password — is pinned in SecuritySettingsComponentTest;
    // the dialogs' one-liner field→intent wiring is the only thing left uncovered.

    @Test
    fun security_connectedDevices_markThisDevice_andRevokeConfirmsFirst() {
        val security = ScriptedSecurityRepository(
            status = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            devices = SecurityResult.Success(
                listOf(
                    ConnectedDevice("tok-this", "Deferno Android — Pixel 10", Instant.parse("2026-06-15T10:30:00Z"), null),
                    ConnectedDevice("tok-other", "Deferno Desktop — newton", Instant.parse("2026-06-20T08:00:00Z"), null),
                ),
            ),
        )
        setContent { SettingsScreen(component(security = security, activeTokenId = "tok-this")) }

        composeRule.onNodeWithText("Security & 2FA").performClick()

        // This install's row is labeled, not revocable; the sibling offers Sign out behind a confirm.
        composeRule.onNodeWithText("This device").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Sign out this device?").assertIsDisplayed()
        assertEquals(emptyList<String>(), security.revokedTokenIds)

        // Confirm inside the dialog (two "Sign out" nodes exist now — pick the dialog's via its sibling title).
        composeRule.onAllNodesWithText("Sign out")[1].performClick()
        assertEquals(listOf("tok-other"), security.revokedTokenIds)
    }
}

/** A scriptable [SecurityRepository] for the View tests — results are flippable mid-test. */
private class ScriptedSecurityRepository(
    var status: SecurityResult<MfaStatus> = SecurityResult.Unavailable,
    var devices: SecurityResult<List<ConnectedDevice>> = SecurityResult.Success(emptyList()),
    var stepUp: SecurityResult<Unit> = SecurityResult.Unavailable,
    var enrollStart: SecurityResult<TotpEnrollment> = SecurityResult.Unavailable,
    var enrollVerify: SecurityResult<List<String>> = SecurityResult.Unavailable,
) : SecurityRepository {
    var disableCalls = 0
    var lastStepUpPassword: String? = null
    val revokedTokenIds = mutableListOf<String>()

    override suspend fun status(): SecurityResult<MfaStatus> = status
    override suspend fun stepUp(password: String): SecurityResult<Unit> {
        lastStepUpPassword = password
        return stepUp
    }

    override suspend fun enrollStart(): SecurityResult<TotpEnrollment> = enrollStart
    override suspend fun enrollVerify(code: String): SecurityResult<List<String>> = enrollVerify
    override suspend fun addEmailBackup(): SecurityResult<Unit> = SecurityResult.Unavailable
    override suspend fun removeEmailBackup(): SecurityResult<Unit> = SecurityResult.Unavailable
    override suspend fun disableMfa(): SecurityResult<Unit> {
        disableCalls++
        return SecurityResult.Success(Unit)
    }

    override suspend fun connectedDevices(): SecurityResult<List<ConnectedDevice>> = devices
    override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> {
        revokedTokenIds += tokenId
        return SecurityResult.Success(Unit)
    }
}

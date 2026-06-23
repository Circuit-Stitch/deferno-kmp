package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.agent.FakeRelayEntitlement
import com.circuitstitch.deferno.core.agent.InMemoryInferenceEnginePreference
import com.circuitstitch.deferno.core.agent.InferenceEngineAvailability
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.data.attachment.InMemoryStorageProviderPreference
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.attachment.StorageProviderAvailability
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.attachment.StorageProviderId
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
        inferenceEngineCatalog: InferenceEngineCatalog = InferenceEngineCatalog.Inert,
        storageProviderCatalog: StorageProviderCatalog = StorageProviderCatalog.Inert,
        keepBrainDumpRecordingsPreference: KeepBrainDumpRecordingsPreference =
            InMemoryKeepBrainDumpRecordingsPreference(),
        brainDumpNotificationPreference: BrainDumpNotificationPreference =
            InMemoryBrainDumpNotificationPreference(),
        shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    ): Triple<DefaultSettingsComponent, FakeSettingsRepository, FakeSettingsEditor> {
        val repo = FakeSettingsRepository(initial)
        val editor = FakeSettingsEditor(repo)
        val component = DefaultSettingsComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            settingsRepository = repo,
            settingsEditor = editor,
            output = output,
            speechEngineCatalog = speechEngineCatalog,
            inferenceEngineCatalog = inferenceEngineCatalog,
            storageProviderCatalog = storageProviderCatalog,
            keepBrainDumpRecordingsPreference = keepBrainDumpRecordingsPreference,
            brainDumpNotificationPreference = brainDumpNotificationPreference,
            shakeToUndoPreference = shakeToUndoPreference,
            coroutineContext = Dispatchers.Unconfined,
        )
        return Triple(component, repo, editor)
    }

    /** A catalog with the cloud relay engine present (so the Agent row is available) + flippable entitlement. */
    private fun agentCatalog(
        selected: InferenceEngineId = InferenceEngineId.Off,
        entitled: Boolean = false,
    ) = InferenceEngineCatalog.forRelay(
        baseUrl = "https://relay/",
        preference = InMemoryInferenceEnginePreference(selected),
        entitlement = FakeRelayEntitlement(entitled),
    )

    /** A catalog offering Automatic + an available Whisper — the Android v1 shape (a real engine present). */
    private fun whisperCatalog() = FakeSpeechEngineCatalog(
        fixedOptions = listOf(
            SpeechEngineOption(SpeechEngineId.Automatic, SpeechAvailability.Available),
            SpeechEngineOption(SpeechEngineId.Whisper, SpeechAvailability.Available),
        ),
        initial = SpeechEngineId.Whisper,
    )

    /** A storage-provider catalog over an in-memory preference seeded with [selected]. */
    private fun storageCatalog(selected: StorageProviderId = StorageProviderId.OnDevice) =
        StorageProviderCatalog(InMemoryStorageProviderPreference(selected))

    @Test
    fun keepBrainDumpRecordings_defaultsOn_andTogglePersistsThroughThePreference() {
        val preference = InMemoryKeepBrainDumpRecordingsPreference()
        val (component, _, _) = component(keepBrainDumpRecordingsPreference = preference)

        // Default on (#211): a new account keeps recordings unless the person opts out.
        assertEquals(true, component.keepBrainDumpRecordings.value)

        component.onKeepBrainDumpRecordingsChanged(false)
        assertEquals(false, component.keepBrainDumpRecordings.value)
        assertEquals(false, preference.enabled(), "device-local — persisted through the preference")

        component.onKeepBrainDumpRecordingsChanged(true)
        assertEquals(true, component.keepBrainDumpRecordings.value)
        assertEquals(true, preference.enabled())
    }

    @Test
    fun brainDumpNotifications_defaultsOff_andTogglePersistsThroughThePreference() {
        val preference = InMemoryBrainDumpNotificationPreference()
        val (component, _, _) = component(brainDumpNotificationPreference = preference)

        // Default off (#266/#271): drafts simply appear in the Inbox; opting in is the consent.
        assertEquals(false, component.brainDumpNotificationsEnabled.value)

        component.onBrainDumpNotificationsChanged(true)
        assertEquals(true, component.brainDumpNotificationsEnabled.value)
        assertEquals(true, preference.enabled(), "device-local — persisted through the preference")

        component.onBrainDumpNotificationsChanged(false)
        assertEquals(false, component.brainDumpNotificationsEnabled.value)
        assertEquals(false, preference.enabled())
    }

    @Test
    fun keepBrainDumpRecordings_seedsFromThePersistedChoice() {
        val (component, _, _) = component(
            keepBrainDumpRecordingsPreference = InMemoryKeepBrainDumpRecordingsPreference(initial = false),
        )
        assertEquals(false, component.keepBrainDumpRecordings.value)
    }

    @Test
    fun shakeToUndo_defaultsOn_andTogglePersistsThroughThePreference() {
        val preference = InMemoryShakeToUndoPreference()
        val (component, _, _) = component(shakeToUndoPreference = preference)

        // Default on (#230): shake-to-undo is enabled unless the person opts out.
        assertEquals(true, component.shakeToUndo.value)

        component.onShakeToUndoChanged(false)
        assertEquals(false, component.shakeToUndo.value)
        assertEquals(false, preference.enabled(), "device-local — persisted through the preference, never synced")

        component.onShakeToUndoChanged(true)
        assertEquals(true, component.shakeToUndo.value)
        assertEquals(true, preference.enabled())
    }

    @Test
    fun shakeToUndo_seedsFromThePersistedChoice() {
        val (component, _, _) = component(
            shakeToUndoPreference = InMemoryShakeToUndoPreference(initial = false),
        )
        assertEquals(false, component.shakeToUndo.value)
    }

    @Test
    fun storageProviderSeedsOnDeviceDefaultWithCloudComingLater() {
        val (component, _, _) = component(storageProviderCatalog = storageCatalog())

        val state = component.storageProvider.value
        assertEquals(StorageProviderId.OnDevice, state.selected)
        // On-device + the Deferno backend are selectable; the user-owned cloud providers are coming-later.
        assertEquals(
            StorageProviderAvailability.Available,
            state.options.first { it.id == StorageProviderId.OnDevice }.availability,
        )
        assertEquals(
            StorageProviderAvailability.ComingLater,
            state.options.first { it.id == StorageProviderId.Dropbox }.availability,
        )
    }

    @Test
    fun onStorageProviderSelectedPersistsAndReflectsTheChoice() {
        val preference = InMemoryStorageProviderPreference()
        val (component, _, _) = component(storageProviderCatalog = StorageProviderCatalog(preference))

        component.onStorageProviderSelected(StorageProviderId.DefernoBackend)

        // Reflected in the read model AND persisted device-locally (never the synced SettingsEditor).
        assertEquals(StorageProviderId.DefernoBackend, component.storageProvider.value.selected)
        assertEquals(StorageProviderId.DefernoBackend, preference.selectedProvider())
    }

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
        // The catalog must render ALL categories (#72): ten backed (incl. the device-local Speech engine
        // #93 + the Agent opt-in #150 + the Storage provider #210) + two coming-soon stubs.
        assertEquals(10, SettingsCategory.entries.count { it.backed })
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

    // --- Agent: the device-local inference-engine choice + per-Account entitlement gate (#150, ADR-0027) ---

    @Test
    fun inferenceEngine_defaultCatalog_isUnavailable_soTheRowHides() {
        // The inert default (shell/Settings without a real catalog): no engine → row hidden, Off selected.
        val (component, _, _) = component()
        assertFalse(component.inferenceEngine.value.available, "no engine in the inert catalog → Agent row hidden")
        assertEquals(InferenceEngineId.Off, component.inferenceEngine.value.selected)
    }

    @Test
    fun inferenceEngine_entitled_offersTheCloudEngineEnabled() {
        val (component, _, _) = component(inferenceEngineCatalog = agentCatalog(entitled = true))
        val state = component.inferenceEngine.value
        assertTrue(state.available, "the relay engine is present → the Agent row shows")
        val cloud = state.options.single()
        assertEquals(InferenceEngineId.DefernoCloud, cloud.id)
        assertEquals(InferenceEngineAvailability.Available, cloud.availability)
    }

    @Test
    fun inferenceEngine_notEntitled_showsTheCloudEngineDisabledPremium() {
        // AC2: not entitled → the row shows but the cloud option is disabled "Premium" — no inference attempted.
        val (component, _, _) = component(inferenceEngineCatalog = agentCatalog(entitled = false))
        val state = component.inferenceEngine.value
        assertTrue(state.available)
        assertEquals(InferenceEngineAvailability.RequiresPremium, state.options.single().availability)
    }

    @Test
    fun selectingAnEngine_persistsThroughTheCatalog_andReflectsImmediately() {
        val catalog = agentCatalog(selected = InferenceEngineId.Off, entitled = true)
        val (component, _, _) = component(inferenceEngineCatalog = catalog)

        component.onInferenceEngineSelected(InferenceEngineId.DefernoCloud)

        // Persisted device-locally through the catalog (App setting) — NOT the synced SettingsEditor.
        assertEquals(InferenceEngineId.DefernoCloud, catalog.selected())
        assertEquals(InferenceEngineId.DefernoCloud, component.inferenceEngine.value.selected)

        component.onInferenceEngineSelected(InferenceEngineId.Off)
        assertEquals(InferenceEngineId.Off, catalog.selected())
        assertEquals(InferenceEngineId.Off, component.inferenceEngine.value.selected)
    }

    @Test
    fun agentCategory_drillsDownAndBacksOut_likeAnyCategory() {
        val (component, _, _) = component(inferenceEngineCatalog = agentCatalog(entitled = true))

        component.openCategory(SettingsCategory.Agent)
        val detail = assertIs<SettingsComponent.SettingsChild.Detail>(component.stack.value.active.instance)
        assertEquals(SettingsCategory.Agent, detail.category)

        assertTrue(component.onBack(), "back pops the Agent detail to the list")
    }
}

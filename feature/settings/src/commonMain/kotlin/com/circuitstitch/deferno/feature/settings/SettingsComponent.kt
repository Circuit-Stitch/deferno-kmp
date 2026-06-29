package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.agent.InferenceEngineOption
import com.circuitstitch.deferno.core.common.asStateFlow
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.attachment.LocalAttachment
import com.circuitstitch.deferno.core.data.attachment.OnDeviceStorageUsage
import com.circuitstitch.deferno.core.data.backup.ImportResult
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.attachment.StorageProviderId
import com.circuitstitch.deferno.core.data.attachment.StorageProviderOption
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.EmptySpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The Settings Destination's category catalog (#72, ADR-0007 tier 3 / ADR-0015). Every wireframe
 * category is listed — the **backed** ones are functional, the two **unbacked** ones ([Security2FA],
 * [Integrations]) render gentle coming-soon stubs (a deliberate, scoped exception to "no dead ends": a
 * settings list missing obvious rows reads as broken). The View renders one row per entry; tapping one
 * drills into [SettingsChild] for it.
 *
 * Most backed categories are driven by the Active Account's synced `UserSettings`; [SpeechEngine] is the
 * exception — a **device-local [[App setting]]** (#93, ADR-0018) over the [SettingsComponent.speechEngine]
 * read model, **not** `UserSettings`, so it never syncs and never changes on an Account switch. The View
 * shows its row only when the device has a real speech engine (hidden on platforms whose engine hasn't
 * landed yet, #94/#95).
 */
enum class SettingsCategory(val backed: Boolean) {
    Appearance(backed = true),
    TaskBehavior(backed = true),
    SpeechEngine(backed = true),
    // The Agent inference-engine choice (#150) — like [SpeechEngine] a device-local [[App setting]] over
    // the [SettingsComponent.inferenceEngine] read model, not `UserSettings`. Row shown only when this
    // device has an inference engine; the cloud option is gated on the Account's relay entitlement.
    Agent(backed = true),
    // The server-mediated Assistant enablement (ADR-0040, #282) — the Owner's persistent disable /
    // withdraw-consent entry point. Unlike the device-local rows above this is a **server** call gated on
    // the Org being `entitled`; the View shows the row only when [SettingsComponent.assistant] is available
    // (iOS-only in v1), so the row is absent on platforms / accounts where the Assistant doesn't apply.
    Assistant(backed = true),
    // The on-device storage-provider choice (#210) — like [SpeechEngine]/[Agent] a device-local
    // [[App setting]] over the [SettingsComponent.storageProvider] read model, not `UserSettings`. Governs
    // where *user/task* attachments are stored (feedback always uses the backend); always shown (on-device
    // is always available).
    Storage(backed = true),
    DataPrivacy(backed = true),
    HelpFeedback(backed = true),
    AppPermissions(backed = true),
    Legal(backed = true),
    Account(backed = true),
    Security2FA(backed = false),
    Integrations(backed = false),
}

/**
 * The Settings Destination's view of the device-local **speech-engine choice** (#93, ADR-0018) — the
 * [SpeechEngine] category's state. [options] is `Automatic` + each real engine on this device (each with
 * its current availability), [selected] is the current device-local choice (default Whisper). [available]
 * is true only when the device has a real engine; the View shows the [SpeechEngine] row only then, so it
 * is hidden on platforms whose engine hasn't landed yet (desktop/iOS pre-#94/#95).
 */
data class SpeechEngineSettings(
    val options: List<SpeechEngineOption>,
    val selected: SpeechEngineId,
) {
    /** Whether a real engine is registered on this device (more than just the `Automatic` strategy). */
    val available: Boolean get() = options.any { it.id != SpeechEngineId.Automatic }

    companion object {
        /** The pre-options seed (no engines queried yet): the persisted choice with an empty option list. */
        fun seed(selected: SpeechEngineId): SpeechEngineSettings = SpeechEngineSettings(emptyList(), selected)
    }
}

/**
 * The Settings Destination's view of the device-local **inference-engine choice** (#150, ADR-0027) — the
 * [SettingsCategory.Agent] category's state. [options] is each engine registered on this device (each with
 * its availability — a cloud engine is `RequiresPremium` until the Account is entitled), [selected] is the
 * current device-local choice (default [InferenceEngineId.Off]). [available] is true only when the device
 * has an engine to offer beyond Off; the View shows the Agent row only then (hidden where no engine has
 * landed yet, like speech). "Off" is the View's always-present default above [options].
 */
data class InferenceEngineSettings(
    val options: List<InferenceEngineOption>,
    val selected: InferenceEngineId,
) {
    /** Whether the device has any engine to offer beyond the implicit Off (drives the row's visibility). */
    val available: Boolean get() = options.isNotEmpty()

    companion object {
        /** The pre-options seed (no engines queried yet): the persisted choice with an empty option list. */
        fun seed(selected: InferenceEngineId): InferenceEngineSettings = InferenceEngineSettings(emptyList(), selected)
    }
}

/**
 * The Settings Destination's view of the device-local **storage-provider choice** (#210) — the
 * [SettingsCategory.Storage] category's state. [options] is on-device (the offline-first default) + the
 * Deferno backend (both selectable) + the user-owned cloud providers (shown coming-later), [selected] is the
 * current device-local choice (default [StorageProviderId.OnDevice]). On-device storage is always available,
 * so the Storage row is always shown (unlike Speech/Agent). It governs *user/task* attachments only —
 * feedback attachments always use the backend.
 */
data class StorageProviderSettings(
    val options: List<StorageProviderOption>,
    val selected: StorageProviderId,
)

/**
 * The Settings Destination's view of **on-device storage usage** (#211) — the [SettingsCategory.Storage]
 * read-out. Today every on-device attachment is a brain-dump recording, so [recordings] is exactly the kept
 * recordings (largest first) and [totalBytes] their summed size. Drives the "Brain-dump recordings — N items"
 * line and the size-sorted list; [Empty] until the first emission (and on hosts that don't wire the seam).
 */
data class StorageUsage(
    val recordings: List<Recording>,
    val totalBytes: Long,
) {
    val count: Int get() = recordings.size

    /** One on-device recording. [createdAtEpochMs] is epoch-millis for a friction-free Swift `Date`. */
    data class Recording(
        val id: String,
        val taskId: String?,
        val filename: String,
        val sizeBytes: Long,
        val createdAtEpochMs: Long,
    )

    companion object {
        val Empty = StorageUsage(emptyList(), 0L)
    }
}

/**
 * The **Settings** Destination component (#72, ADR-0013 / ADR-0007 tier 3): a **drill-down** modelled
 * as a Decompose [ChildStack] — the category [SettingsChild.List] at the root, and a per-category
 * detail pushed above it ([openCategory]) and popped cleanly back ([onBack]). It is Compose-free and
 * iOS-capable (ADR-0004): the View renders [stack].
 *
 * The backed categories observe the Active Account's [UserSettings] as one shared [settings] StateFlow
 * (offline-first, ADR-0001 — seeded with [UserSettings.Default] so the detail screens always have a
 * value) and write through the narrow [SettingsEditor] seam (#173): the shell backs it with the command
 * registry (ADR-0007 — each intent a per-field `SettingsCommand`, dispatched to the optimistic local
 * apply + outbox enqueue, so Appearance applies **live** because the same `Flow` drives the app-wide
 * theme). Host-level concerns the slice
 * can't own — opening OS app settings, the Zitadel console URL, or the Profile Destination — are
 * emitted as [Output] for the shell to route (the same Output-up routing Plan/Tasks/Profile use).
 */
interface SettingsComponent {
    /** The tier-3 drill-down: the category list at the root, a category detail pushed above it. */
    val stack: Value<ChildStack<*, SettingsChild>>

    /**
     * [stack]'s active child mirrored as a [StateFlow] for the SwiftUI Views to observe via SKIE (which
     * bridges `StateFlow` + the sealed [SettingsChild] → a Swift enum, but not Decompose's [Value]).
     */
    val activeChild: StateFlow<SettingsChild>

    /** The Active Account's settings — drives every backed category and the app-wide live theme. */
    val settings: StateFlow<UserSettings>

    /**
     * The device-local speech-engine choice ([SettingsCategory.SpeechEngine], #93, ADR-0018) — an
     * **[[App setting]]**, sourced from the AppScope speech catalog, **not** the synced [settings]. It
     * never syncs to the backend and never changes when the Active Account switches.
     */
    val speechEngine: StateFlow<SpeechEngineSettings>

    /**
     * The Agent's device-local inference-engine choice ([SettingsCategory.Agent], #150, ADR-0027) — the
     * engine selection (Off / on-device / cloud) over the Active Account's relay entitlement, sourced from
     * the AppScope [InferenceEngineCatalog], **not** the synced [settings]. It never syncs; the View shows
     * the row only when [InferenceEngineSettings.available], with the cloud option disabled until entitled.
     */
    val inferenceEngine: StateFlow<InferenceEngineSettings>

    /**
     * The server-mediated **Assistant enablement** ([SettingsCategory.Assistant], ADR-0040) — the gate
     * over the Owner's enable/disable (egress consent), sourced from the [AssistantEnablement] seam (the
     * AppScope `AssistantClient` + the personal org), **not** the synced [settings]. The View shows the
     * row only when [AssistantSettings.available] (the Org is `entitled`, iOS-only in v1).
     */
    val assistant: StateFlow<AssistantSettings>

    /**
     * The device-local storage-provider choice ([SettingsCategory.Storage], #210) — where new *user/task*
     * attachments are stored (on-device default / Deferno backend / user-owned cloud coming-later), sourced
     * from the AppScope [StorageProviderCatalog], **not** the synced [settings]. It never syncs and never
     * changes on an Account switch. Feedback attachments are unaffected (always backend).
     */
    val storageProvider: StateFlow<StorageProviderSettings>

    /**
     * On-device storage usage for the [SettingsCategory.Storage] read-out (#211) — the kept brain-dump
     * recordings (largest first) and their total size, sourced from the AccountScope on-device store via the
     * [OnDeviceStorageUsage] seam. Read-only and offline-first; [StorageUsage.Empty] until the first emission
     * (and on hosts that don't wire the seam).
     */
    val storageUsage: StateFlow<StorageUsage>

    /**
     * The device-local **"keep brain-dump recordings"** choice (#211) — whether a brain-dump's source
     * recording is retained as an on-device Task attachment (#210) when a draft is accepted in the Inbox.
     * An **[[App setting]]**, sourced from the AppScope preference, **not** the synced [settings]; it never
     * syncs and never changes on an Account switch. Defaults to on. Surfaced under [SettingsCategory.Storage]
     * (recordings are on-device attachments); the Android View renders the toggle (desktop/iOS don't capture).
     */
    val keepBrainDumpRecordings: StateFlow<Boolean>

    /**
     * The device-local **"Brain dump notifications"** opt-in (#266/#271) — whether a completion notification
     * fires when a Brain dump finishes (drafts ready, or a recording saved to review). An **[[App setting]]**,
     * sourced from the AppScope preference, **not** the synced [settings]; never syncs, never changes on an
     * Account switch. Defaults to **off** (drafts simply appear in the [[Inbox]]); enabling it is the consent,
     * and on iOS the point notification authorization is requested. The iOS View renders the toggle.
     */
    val brainDumpNotificationsEnabled: StateFlow<Boolean>

    /**
     * The device-local **"shake to undo"** choice ([SettingsCategory.TaskBehavior], ADR-0034 decision 8,
     * #230) — whether a phone shake on the Tasks tree raises the "Undo [operation]?" confirm that reverts the
     * last Move. An **[[App setting]]**, sourced from the AppScope preference, **not** the synced [settings];
     * it never syncs and never changes on an Account switch. Defaults to on; shake is never the only undo
     * path (the snackbar + menu remain), and the confirm prompt is the accidental-fire safety.
     */
    val shakeToUndo: StateFlow<Boolean>

    /** Drill into [category]'s detail (push). */
    fun openCategory(category: SettingsCategory)

    /** Back out of an open category detail to the list (pop); `false` when already at the list root. */
    fun onBack(): Boolean

    // --- backed-category intents (optimistic apply + persist via the command-backed SettingsEditor) ---

    /** Appearance: set the theme family — applies live + persists (#72). */
    fun onThemeFamilyChanged(family: ThemeFamily)

    /** Appearance: set the theme mode (light/dark/auto) — applies live + persists (#72). */
    fun onThemeModeChanged(mode: ThemeMode)

    /** Task behavior: toggle the experimental drag-and-drop affordance. */
    fun onDragAndDropChanged(enabled: Boolean)

    /**
     * Task behavior: toggle device-local shake-to-undo (#230) — persists device-locally via the preference,
     * **never** synced. When off, a shake does nothing; the snackbar + menu undo paths still revert a Move.
     */
    fun onShakeToUndoChanged(enabled: Boolean)

    /** Task behavior: set the done-visibility windows in seconds (`null` clears a window). */
    fun onDoneVisibilityChanged(globalSeconds: Long?, dashboardSeconds: Long?)

    /** Data & Privacy: toggle analytics/tracking. */
    fun onTrackingChanged(enabled: Boolean)

    /**
     * Speech engine: set the device-local engine choice (#93, ADR-0018) — persists device-locally via the
     * speech catalog, **never** synced. A chosen-but-unavailable engine still records the preference; the
     * [com.circuitstitch.deferno.core.speech.SpeechToTextSelector] falls back to the whisper floor at
     * `listen()` time (never cloud), so the choice is honoured the moment that engine becomes available.
     */
    fun onSpeechEngineSelected(id: SpeechEngineId)

    /**
     * Agent: set the device-local inference-engine choice (#150, ADR-0027) — persists device-locally via
     * the [InferenceEngineCatalog], **never** synced. Selecting the cloud engine is the explicit opt-in;
     * the gate still requires the Account to be entitled before any cloud call is made. Selecting an
     * on-device engine is ungated; [InferenceEngineId.Off] stands the Agent fully down.
     */
    fun onInferenceEngineSelected(id: InferenceEngineId)

    /**
     * Assistant: enable or disable the server-mediated Assistant for the Org (ADR-0040) — a **server** call
     * through the [AssistantEnablement] seam (re-checked against entitlement server-side). Enabling carries
     * the egress consent (the View shows [AssistantSettings.disclosure] first); disabling withdraws it.
     */
    fun onAssistantEnablementChanged(enabled: Boolean)

    /**
     * Storage provider: set the device-local choice (#210) — persists device-locally via the
     * [StorageProviderCatalog], **never** synced. Governs only *user/task* attachments (on-device keeps bytes
     * on the device; the user-owned cloud providers are coming-later); feedback attachments always use the backend.
     */
    fun onStorageProviderSelected(id: StorageProviderId)

    /**
     * Storage: toggle whether new brain-dump recordings are kept on-device (#211) — persists device-locally
     * via the preference, **never** synced. When off, a brain dump's recording is not retained on accept.
     */
    fun onKeepBrainDumpRecordingsChanged(enabled: Boolean)

    /**
     * Brain dump: toggle whether a completion notification fires (#266/#271) — persists device-locally via
     * the preference, **never** synced. The View requests OS notification authorization when turning it on
     * (the opt-in is the consent); a denial is handled there and leaves the setting reflecting reality.
     */
    fun onBrainDumpNotificationsChanged(enabled: Boolean)

    // --- host-routed intents (Output up to the shell) ---

    /**
     * Data & Privacy: build the on-device Backup zip (#313, ADR-0041) — a one-shot, offline read of the
     * local stores serialized to a `manifest.json`-only zip whose manifest **is** the REST envelope. The
     * host shares the bytes (iOS share sheet). The web-redirect [onOpenDataExportImport] is now iOS-dead.
     */
    suspend fun buildBackupZip(): ByteArray

    /**
     * Data & Privacy: restore items from an on-device Backup zip (#314, ADR-0041) — parse + version-gate +
     * replay each item as an id-preserving create on the offline outbox (idempotent via ADR-0034). The host
     * supplies the picked file's bytes (iOS document picker) and surfaces the [ImportResult] to the person.
     */
    suspend fun importBackup(bytes: ByteArray): ImportResult

    /**
     * Data & Privacy: ask the host to open the web app's export/import surface. Still used by Android/
     * desktop/macOS (no in-app export there yet); on iOS it is superseded by the in-app [buildBackupZip]
     * export action (#313) and no longer invoked (AC #3, ADR-0015).
     */
    fun onOpenDataExportImport()

    /**
     * Help & Feedback: ask the shell to open the in-app Feedback form (#375). Compose-free here — the
     * slice only emits; the shell opens the Feedback overlay (the same primitive Search/New ride).
     */
    fun onOpenSubmitFeedback()

    /** App Permissions: ask the host to deep-link to the OS app-settings screen. */
    fun onOpenAppPermissions()

    /** Security & 2FA (stub): ask the host to open the Zitadel console URL when present. */
    fun onOpenConsole()

    /** Account: ask the host to open the Profile Destination for identity. */
    fun onOpenProfile()

    /**
     * Storage: open an on-device recording's owning Task (#211) — or the [[Inbox]] when it's an un-triaged
     * placeholder ([taskId] null, not yet attached to a Task). Host-routed: the shell owns the Destination graph.
     */
    fun onOpenRecording(taskId: String?)

    /** A live category detail, tagged with which [SettingsCategory] it is (for the unbacked stubs). */
    sealed interface SettingsChild {
        /** The category list root. */
        data object List : SettingsChild

        /** A category detail; [category] selects which screen the View renders. */
        data class Detail(val category: SettingsCategory) : SettingsChild
    }

    sealed interface Output {
        /** Open the OS app-settings screen for this app (App Permissions category). */
        data object OpenOsAppSettings : Output

        /** Open the web app's data export/import surface (Data & Privacy — no client endpoint at v0.1). */
        data object OpenDataExportImport : Output

        /** Open the in-app Feedback form (Help & Feedback — the shell hosts it as an overlay, #375). */
        data object OpenSubmitFeedback : Output

        /** Open the Zitadel admin console (Security & 2FA stub) — handled only when a URL is present. */
        data object OpenConsoleUrl : Output

        /** Switch to the Profile Destination (Account category links to identity). */
        data object OpenProfile : Output

        /** Open a recording's owning Task in the Tasks Destination (a Storage row tap, #211). */
        data class OpenTask(val taskId: String) : Output

        /** Open the Inbox for an un-triaged recording placeholder (a Storage row tap, #211). */
        data object OpenInbox : Output
    }
}

class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val settingsRepository: SettingsRepository,
    private val settingsEditor: SettingsEditor,
    private val output: (SettingsComponent.Output) -> Unit = {},
    // The device-local speech-engine [[App setting]] (#93, ADR-0018): the AppScope catalog over the
    // registered engines + device-local preference. Defaulted to the inert [EmptySpeechEngineCatalog] so
    // the many existing Settings tests build without supplying it — it yields only "Automatic", so the
    // SpeechEngine row stays hidden (the analogue of the shell's UnavailableSpeechToText default).
    private val speechEngineCatalog: SpeechEngineCatalog = EmptySpeechEngineCatalog,
    // The device locale the catalog queries engine availability for (a non-English locale reports
    // unavailable rather than mis-transcribing, ADR-0018). Defaulted for tests.
    private val locale: String = "en-US",
    // The Agent inference-engine choice + entitlement gate (#150, ADR-0027): the AppScope catalog over the
    // device-local engine selection + the Account's relay entitlement. Defaulted to the inert
    // [InferenceEngineCatalog.Inert] (no engine → row hidden) so existing Settings tests build without
    // supplying it (like the speech default).
    private val inferenceEngineCatalog: InferenceEngineCatalog = InferenceEngineCatalog.Inert,
    // The server-mediated Assistant enablement seam (ADR-0040, #282): the AppScope `AssistantClient` + the
    // resolved personal org, gated on entitlement. Defaulted to [AssistantEnablement.Inert] (every call
    // null → the Assistant row hides) so existing Settings tests / non-iOS hosts build without supplying it.
    private val assistantEnablement: AssistantEnablement = AssistantEnablement.Inert,
    // The device-local storage-provider choice (#210): the AppScope catalog over the device-local
    // preference. Defaulted to the inert [StorageProviderCatalog.Inert] (in-memory selection) so existing
    // Settings tests build without supplying it; on-device is always available, so the Storage row always shows.
    private val storageProviderCatalog: StorageProviderCatalog = StorageProviderCatalog.Inert,
    // The on-device storage usage read seam (#211): the AccountScope on-device store surfaced for the Storage
    // read-out. Defaulted to [OnDeviceStorageUsage.Inert] (empty) so existing Settings tests build without
    // supplying it; the shell backs it with `localAttachmentRepository`.
    private val onDeviceStorageUsage: OnDeviceStorageUsage = OnDeviceStorageUsage.Inert,
    // The on-device Backup-zip builder (#313, ADR-0041). Defaulted to an empty zip so existing Settings
    // tests build without it; the shell backs it with `session::buildBackupZip`. Named `buildBackup` (not
    // `buildBackupZip`) so the override below isn't a self-recursive call into the same-named member fn.
    private val buildBackup: suspend () -> ByteArray = { ByteArray(0) },
    // The on-device Backup import/restore seam (#314, ADR-0041). Defaulted to [ImportResult.Malformed] so
    // existing Settings tests build without it; the shell backs it with `session::importBackup`. Named
    // `restoreBackup` (not `importBackup`) so the override below isn't a self-recursive call.
    private val restoreBackup: suspend (ByteArray) -> ImportResult = { ImportResult.Malformed },
    // The device-local "keep brain-dump recordings" preference (#211). Defaulted to an in-memory (on)
    // preference so existing Settings tests build without supplying it (like the storage-catalog default).
    private val keepBrainDumpRecordingsPreference: KeepBrainDumpRecordingsPreference =
        InMemoryKeepBrainDumpRecordingsPreference(),
    // The device-local "Brain dump notifications" opt-in (#266/#271). Defaulted to an in-memory (off)
    // preference so existing Settings tests build without supplying it (like the keep-recordings default).
    private val brainDumpNotificationPreference: BrainDumpNotificationPreference =
        InMemoryBrainDumpNotificationPreference(),
    // The device-local shake-to-undo preference (#230). Defaulted to an in-memory (on) preference so existing
    // Settings tests build without supplying it (like the keep-recordings default).
    private val shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SettingsComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    // Plain-data configs (serializer = null → no state restoration wired in v1, matching the feature
    // components). The root is List; each category detail is pushed as Category(category).
    private sealed interface Config {
        data object List : Config
        data class Category(val category: SettingsCategory) : Config
    }

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, SettingsComponent.SettingsChild>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = Config.List,
            key = "SettingsStack",
            handleBackButton = false, // tier-3 back is routed via onBack(), not a global stack pop
            childFactory = ::createChild,
        )

    override val activeChild: StateFlow<SettingsComponent.SettingsChild> =
        stack.asStateFlow(scope) { it.active.instance }

    override val settings: StateFlow<UserSettings> =
        settingsRepository.observeSettings()
            .stateIn(scope, SharingStarted.Eagerly, UserSettings.Default)

    // The device-local speech-engine choice (#93). Seeded synchronously with the persisted choice (an
    // empty option list → row hidden); the init below fills the options once each engine's suspend
    // availability resolves. Device-local — sourced from the AppScope catalog, never the synced settings.
    private val _speechEngine = MutableStateFlow(SpeechEngineSettings.seed(speechEngineCatalog.selected()))
    override val speechEngine: StateFlow<SpeechEngineSettings> = _speechEngine.asStateFlow()

    // The Agent inference-engine choice (#150). Seeded synchronously with the persisted selection (an empty
    // option list → row hidden); the init below fills the options once entitlement (a suspend read)
    // resolves. Device-local — sourced from the AppScope catalog, never the synced settings.
    private val _inferenceEngine = MutableStateFlow(InferenceEngineSettings.seed(inferenceEngineCatalog.selected()))
    override val inferenceEngine: StateFlow<InferenceEngineSettings> = _inferenceEngine.asStateFlow()

    // The server-mediated Assistant enablement (ADR-0040). Seeded empty (availability null → row hidden); the
    // init below observes the shared [AssistantEnablement.availability] flow (the same source the Destination
    // reads). Server-sourced via the seam — never synced settings, hidden unless the Org is entitled (so
    // absent on non-iOS hosts / accounts in v1).
    private val _assistant = MutableStateFlow(AssistantSettings())
    override val assistant: StateFlow<AssistantSettings> = _assistant.asStateFlow()

    // The device-local storage-provider choice (#210). Seeded synchronously with the full static option list
    // + the persisted selection — on-device is always available, so no suspend availability query and the
    // Storage row always shows. Device-local — sourced from the AppScope catalog, never the synced settings.
    private val _storageProvider = MutableStateFlow(
        StorageProviderSettings(storageProviderCatalog.options(), storageProviderCatalog.selected()),
    )
    override val storageProvider: StateFlow<StorageProviderSettings> = _storageProvider.asStateFlow()

    // On-device storage usage (#211) — observe the seam's recordings Flow (largest first), mapped to the view
    // model + summed. Offline-first (ADR-0001); inert seam → [StorageUsage.Empty] on unwired hosts.
    override val storageUsage: StateFlow<StorageUsage> =
        onDeviceStorageUsage.brainDumpRecordings()
            .map { rows -> StorageUsage(rows.map { it.toRecording() }, rows.sumOf { it.size }) }
            .stateIn(scope, SharingStarted.Eagerly, StorageUsage.Empty)

    // The device-local "keep brain-dump recordings" choice (#211). Seeded synchronously from the preference;
    // device-local — sourced from the AppScope preference, never the synced settings.
    private val _keepBrainDumpRecordings = MutableStateFlow(keepBrainDumpRecordingsPreference.enabled())
    override val keepBrainDumpRecordings: StateFlow<Boolean> = _keepBrainDumpRecordings.asStateFlow()

    // The device-local "Brain dump notifications" opt-in (#266/#271). Seeded synchronously from the
    // preference; device-local — sourced from the AppScope preference, never the synced settings.
    private val _brainDumpNotificationsEnabled = MutableStateFlow(brainDumpNotificationPreference.enabled())
    override val brainDumpNotificationsEnabled: StateFlow<Boolean> = _brainDumpNotificationsEnabled.asStateFlow()

    // The device-local shake-to-undo choice (#230). Seeded synchronously from the preference; device-local —
    // sourced from the AppScope preference, never the synced settings.
    private val _shakeToUndo = MutableStateFlow(shakeToUndoPreference.enabled())
    override val shakeToUndo: StateFlow<Boolean> = _shakeToUndo.asStateFlow()

    init {
        scope.launch {
            _speechEngine.value = SpeechEngineSettings(
                options = speechEngineCatalog.options(locale),
                selected = speechEngineCatalog.selected(),
            )
        }
        scope.launch {
            _inferenceEngine.value = InferenceEngineSettings(
                options = inferenceEngineCatalog.options(),
                selected = inferenceEngineCatalog.selected(),
            )
        }
        // The Assistant gate is a shared, observable source — the shell feeds the SAME flow to the Assistant
        // Destination — so a flip from either surface reflects here. [refresh] kicks the (server) fetch; the
        // collect republishes it. A null gate (not entitled / offline / non-iOS) leaves the row hidden.
        scope.launch { assistantEnablement.refresh() }
        scope.launch {
            assistantEnablement.availability.collect { gate ->
                _assistant.value = _assistant.value.copy(availability = gate)
            }
        }
    }

    // `push` is a DelicateDecomposeApi (a direct imperative push rather than a navigate-transform);
    // intentional here — a tier-3 drill-down is exactly a push/pop stack (the same shape the docs note
    // push is fine for), and the configs are plain data so there is no transform to reuse.
    @OptIn(DelicateDecomposeApi::class)
    override fun openCategory(category: SettingsCategory) {
        navigation.push(Config.Category(category))
    }

    override fun onBack(): Boolean =
        if (stack.value.active.configuration is Config.Category) {
            navigation.pop()
            true
        } else {
            false
        }

    override fun onThemeFamilyChanged(family: ThemeFamily) {
        scope.launch { settingsEditor.setTheme(family, settings.value.themeMode) }
    }

    override fun onThemeModeChanged(mode: ThemeMode) {
        scope.launch { settingsEditor.setTheme(settings.value.themeFamily, mode) }
    }

    override fun onDragAndDropChanged(enabled: Boolean) {
        scope.launch { settingsEditor.setDragAndDrop(enabled) }
    }

    override fun onShakeToUndoChanged(enabled: Boolean) {
        // Device-local persist (App setting, #230) — not the synced SettingsEditor.
        shakeToUndoPreference.setEnabled(enabled)
        _shakeToUndo.value = enabled
    }

    override fun onDoneVisibilityChanged(globalSeconds: Long?, dashboardSeconds: Long?) {
        scope.launch { settingsEditor.setDoneVisibility(globalSeconds, dashboardSeconds) }
    }

    override fun onTrackingChanged(enabled: Boolean) {
        scope.launch { settingsEditor.setTracking(enabled) }
    }

    override fun onSpeechEngineSelected(id: SpeechEngineId) {
        // Device-local persist (App setting, #93) — not the synced SettingsEditor. A selection doesn't
        // change availability, so reflect the new choice in place rather than re-querying the options.
        speechEngineCatalog.select(id)
        _speechEngine.value = _speechEngine.value.copy(selected = id)
    }

    override fun onInferenceEngineSelected(id: InferenceEngineId) {
        // Device-local persist (App setting, #150) — not the synced SettingsEditor. A selection doesn't
        // change availability/entitlement, so reflect the new choice in place rather than re-querying.
        inferenceEngineCatalog.select(id)
        _inferenceEngine.value = _inferenceEngine.value.copy(selected = id)
    }

    override fun onAssistantEnablementChanged(enabled: Boolean) {
        // Server call (ADR-0040) — guard against a concurrent in-flight flip. The result lands in the shared
        // [AssistantEnablement.availability] flow (so the Destination reflects it too); on failure the flow is
        // unchanged, so the toggle reverts to reality (never a silent flip). We just clear the in-flight guard.
        if (_assistant.value.busy) return
        _assistant.value = _assistant.value.copy(busy = true)
        scope.launch {
            assistantEnablement.setEnabled(enabled)
            _assistant.value = _assistant.value.copy(busy = false)
        }
    }

    override fun onStorageProviderSelected(id: StorageProviderId) {
        // Device-local persist (App setting, #210) — not the synced SettingsEditor. The static options don't
        // change on selection, so reflect the new choice in place.
        storageProviderCatalog.select(id)
        _storageProvider.value = _storageProvider.value.copy(selected = id)
    }

    override fun onKeepBrainDumpRecordingsChanged(enabled: Boolean) {
        // Device-local persist (App setting, #211) — not the synced SettingsEditor.
        keepBrainDumpRecordingsPreference.setEnabled(enabled)
        _keepBrainDumpRecordings.value = enabled
    }

    override fun onBrainDumpNotificationsChanged(enabled: Boolean) {
        // Device-local persist (App setting, #266/#271) — not the synced SettingsEditor. The View owns the
        // OS authorization request on enable; persisting the choice here is independent of that grant.
        brainDumpNotificationPreference.setEnabled(enabled)
        _brainDumpNotificationsEnabled.value = enabled
    }

    override suspend fun buildBackupZip(): ByteArray = buildBackup()

    override suspend fun importBackup(bytes: ByteArray): ImportResult = restoreBackup(bytes)

    override fun onOpenDataExportImport() = output(SettingsComponent.Output.OpenDataExportImport)

    override fun onOpenSubmitFeedback() = output(SettingsComponent.Output.OpenSubmitFeedback)

    override fun onOpenAppPermissions() = output(SettingsComponent.Output.OpenOsAppSettings)

    override fun onOpenConsole() = output(SettingsComponent.Output.OpenConsoleUrl)

    override fun onOpenProfile() = output(SettingsComponent.Output.OpenProfile)

    override fun onOpenRecording(taskId: String?) =
        output(if (taskId != null) SettingsComponent.Output.OpenTask(taskId) else SettingsComponent.Output.OpenInbox)

    private fun createChild(
        config: Config,
        @Suppress("UNUSED_PARAMETER") childContext: ComponentContext,
    ): SettingsComponent.SettingsChild =
        when (config) {
            Config.List -> SettingsComponent.SettingsChild.List
            is Config.Category -> SettingsComponent.SettingsChild.Detail(config.category)
        }
}

private fun LocalAttachment.toRecording(): StorageUsage.Recording = StorageUsage.Recording(
    id = id,
    taskId = taskId,
    filename = filename,
    sizeBytes = size,
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
)

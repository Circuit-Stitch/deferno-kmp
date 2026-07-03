package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.circuitstitch.deferno.core.common.asStateFlow
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.backup.ImportResult
import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.SearchSeed
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.agent.InferenceEngineCatalog
import com.circuitstitch.deferno.core.data.attachment.OnDeviceStorageUsage
import com.circuitstitch.deferno.core.data.attachment.StorageProviderCatalog
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.speech.EmptySpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.UnavailableSpeechToText
import com.circuitstitch.deferno.feature.assistant.AssistantComponent
import com.circuitstitch.deferno.feature.assistant.AssistantStream
import com.circuitstitch.deferno.feature.assistant.DefaultAssistantComponent
import com.circuitstitch.deferno.feature.settings.AssistantEnablement
import com.circuitstitch.deferno.feature.braindumps.AcceptResult
import com.circuitstitch.deferno.feature.braindumps.DefaultInboxComponent
import com.circuitstitch.deferno.feature.braindumps.InboxComponent
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.calendar.DefaultCalendarComponent
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.profile.DefaultProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsEditor
import com.circuitstitch.deferno.feature.tasks.DefaultSearchComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachments
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.SearchTasks
import com.circuitstitch.deferno.feature.tasks.TaskMenuState
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.feature.tasks.DefinitionStateEditor
import com.circuitstitch.deferno.feature.tasks.MoveEditor
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Instant
import kotlin.coroutines.CoroutineContext

class DefaultMainShellComponent(
    componentContext: ComponentContext,
    // The cross-kind Item read + device-local fold store the Tasks Item tree renders (ADR-0034, #226/#227).
    private val itemRepository: ItemRepository,
    private val foldStore: ItemFoldStore,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    // The Settings Destination's User-setting write seam (#72/#173): command-backed in production
    // (AccountSession routes each intent through the command executor, ADR-0007).
    private val settingsEditor: SettingsEditor,
    // The Security & 2FA seam (Settings → Security): defaulted inert so the many shell tests build
    // without supplying it (the category then renders its unavailable state, never a crash).
    private val securityRepository: SecurityRepository = SecurityRepository.Inert,
    private val account: Account,
    private val today: LocalDate,
    private val timeZone: String,
    // The Calendar Destination's read source + occurrence-act seam (#74). Defaulted to no-ops so the
    // many shell tests build without supplying them (mirrors workingStateEditor/searchTasks/create).
    private val calendarRepository: CalendarRepository = NoopCalendarRepository,
    private val occurrenceEditor: OccurrenceEditor = NoopOccurrenceEditor,
    // The Tasks working-state write seam (#73), threaded into the Tasks Destination's component so its
    // detail can issue lifecycle Commands. Defaults to a no-op so the many shell tests build without it.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The Tasks Item-tree non-Task status seam (#299), threaded into the Tasks Destination so its command
    // menu can Archive/Restore a recurring definition. Defaults to a no-op so the many shell tests build
    // without it (like workingStateEditor).
    private val definitionStateEditor: DefinitionStateEditor = DefinitionStateEditor.NONE,
    // The Tasks Item-tree move seam (#228), threaded into the Tasks Destination so its modal move mode can
    // issue Move Commands. Defaults to a no-op so the many shell tests build without it.
    private val moveEditor: MoveEditor = MoveEditor.NONE,
    // The Task detail's online-only comments + attachments source, threaded into the detail (overlay +
    // Tasks Destination). Defaulted to the no-op so the many shell tests build without supplying it.
    private val taskDetailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    // The Task detail's editable-PROPERTIES write seams (DUE date + LABELS), threaded into the detail
    // (overlay + Tasks Destination). Defaulted to no-ops so the many shell tests build without them.
    private val setDeadline: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    private val setLabels: suspend (TaskId, List<String>) -> Unit = { _, _ -> },
    // The Task detail's destructive Delete seam (kebab → confirm), threaded into the detail (overlay +
    // Tasks Destination). Defaults to a no-op so the many shell tests build without it.
    private val deleteTask: suspend (TaskId) -> Unit = { _ -> },
    // The Item-tree command menu's Task-only Pin + plan-toggle seams (#231), threaded into the Tasks
    // Destination. [addToPlan]/[removeFromPlan] are pre-bound to (today, timeZone) by the RootComponent; the
    // per-row Task state (status/Pin/plan labels) is joined below off the Task list + today's plan. All
    // default to no-ops so the many shell tests build without supplying them.
    private val setPinned: suspend (TaskId, Boolean) -> Unit = { _, _ -> },
    private val addToPlan: suspend (TaskId) -> Unit = { _ -> },
    private val removeFromPlan: suspend (TaskId) -> Unit = { _ -> },
    // The Task detail's on-device attachment seam (#211), threaded into the Tasks destination + the
    // TaskDetail overlay. Defaults to the empty NONE so the many shell tests build without it; production
    // threads this Account's seam from the session.
    private val onDeviceAttachments: OnDeviceAttachments = OnDeviceAttachments.NONE,
    // On-device storage usage for the Settings > Storage read-out (#211), threaded into the Settings
    // destination. Defaults to Inert (empty) so the many shell tests build without it; production threads
    // this Account's seam from the session.
    private val onDeviceStorageUsage: OnDeviceStorageUsage = OnDeviceStorageUsage.Inert,
    // On-device Backup export (#313, ADR-0041), threaded into the Settings destination. Defaults to an
    // empty zip so the many shell tests build without it; production threads session::buildBackupZip.
    private val buildBackupZip: suspend () -> ByteArray = { ByteArray(0) },
    // On-device Backup import/restore (#314, ADR-0041), threaded into the Settings destination. Defaults
    // to Malformed so the many shell tests build without it; production threads session::importBackup.
    private val importBackup: suspend (ByteArray) -> ImportResult = { ImportResult.Malformed },
    // The global-search seam (#73): a one-shot, online-only pull the Search overlay drives. Defaults
    // to "no results" so tests that don't exercise Search build without supplying it.
    private val searchTasks: SearchTasks = SearchTasks { _ -> emptyList() },
    // The in-app Help → Feedback service (#375) the Feedback overlay submits through (presign → PUT →
    // submit). AppScope; defaulted to a no-op Offline so shell tests build without supplying it.
    private val feedbackRepository: FeedbackRepository = NoopFeedbackRepository,
    override val accounts: StateFlow<List<Account>> = MutableStateFlow(emptyList()),
    override val activeAccount: StateFlow<Account?> = MutableStateFlow(null),
    private val onSwitchAccount: (AccountId) -> Unit = {},
    private val output: (MainShellComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
    // The online-only create seam (#71, ADR-0016) the New overlay dispatches through. Defaulted to a
    // no-op Offline so a test that doesn't exercise create needn't supply it.
    private val create: suspend (com.circuitstitch.deferno.core.domain.command.CreateItem.Payload) -> com.circuitstitch.deferno.core.domain.command.CommandResult =
        { com.circuitstitch.deferno.core.domain.command.CommandResult.Offline(com.circuitstitch.deferno.core.domain.command.CommandKind.CreateItem) },
    // Dictation (#92, ADR-0018): the on-device SpeechToText the New surface's mic drives, and the device
    // locale it recognizes. Defaulted to the Unavailable floor / English so the many shell tests build
    // without supplying them — dictation simply offers no mic.
    private val speechToText: SpeechToText = UnavailableSpeechToText,
    private val locale: String = "en-US",
    // The device-local speech-engine [[App setting]] (#93, ADR-0018): the AppScope catalog the Settings
    // Destination reads (registered engines + availability + the device-local choice). Defaulted to the
    // inert [EmptySpeechEngineCatalog] (only "Automatic" → the Settings row hides) so the many shell tests
    // build without it; production threads the real catalog from the AppComponent (like speechToText).
    private val speechEngineCatalog: SpeechEngineCatalog = EmptySpeechEngineCatalog,
    // The Agent inference-engine choice + entitlement gate (#150, ADR-0027): the AppScope catalog the
    // Settings Destination reads. Defaulted to the inert [InferenceEngineCatalog.Inert] (no engine → the
    // Agent row hides) so the many shell tests build without it; production threads the real catalog from
    // the AppComponent (like speechEngineCatalog).
    private val inferenceEngineCatalog: InferenceEngineCatalog = InferenceEngineCatalog.Inert,
    // The device-local storage-provider choice (#210): the AppScope catalog the Settings Destination reads.
    // Defaulted to the inert [StorageProviderCatalog.Inert] (in-memory selection) so the many shell tests
    // build without it; production threads the real catalog from the AppComponent (like inferenceEngineCatalog).
    private val storageProviderCatalog: StorageProviderCatalog = StorageProviderCatalog.Inert,
    // The device-local "keep brain-dump recordings" App setting (#211) the Settings Destination renders.
    // Defaulted to an in-memory (on) preference so the many shell tests build without it; production threads
    // the real preference from the AppComponent (like storageProviderCatalog).
    private val keepBrainDumpRecordingsPreference: KeepBrainDumpRecordingsPreference =
        InMemoryKeepBrainDumpRecordingsPreference(),
    // The device-local "Brain dump notifications" opt-in (#266/#271) the Settings Destination renders.
    // Defaulted to an in-memory (off) preference so the many shell tests build without it; production threads
    // the real preference from the AppComponent (like keepBrainDumpRecordingsPreference).
    private val brainDumpNotificationPreference: BrainDumpNotificationPreference =
        InMemoryBrainDumpNotificationPreference(),
    // The device-local shake-to-undo App setting (#230) the Tasks tree + Settings render. Defaulted to an
    // in-memory (on) preference so the many shell tests build without it; production threads the real
    // preference from the AppComponent (like keepBrainDumpRecordingsPreference).
    private val shakeToUndoPreference: ShakeToUndoPreference = InMemoryShakeToUndoPreference(),
    // The Brain dump's record-to-file seam (ADR-0027/#150, Stage 4): records the mic to a WAV and, on Stop
    // (job cancellation), hands it to the background worker — transcription/extraction run there and the
    // drafts land in the Inbox, so the overlay no longer needs the inference engine. Android-only (it needs
    // a Context + WorkManager); desktop/tests leave it the no-op default — the recorder is simply inert.
    // The shell closes the injected today/timeZone over it (no Clock.System) for the worker's date context.
    private val recordBrainDump: suspend (LocalDate, String) -> Unit = { _, _ -> },
    // The New surface's PermissionPermanentlyDenied affordance (#120), host-routed to the OS settings
    // surface for the foreclosed dictation permission. Defaulted to a no-op like the other host actions.
    private val onOpenDictationPermissionSettings: () -> Unit = {},
    // The Inbox Destination's draft store (ADR-0015 Inbox amendment): the live draft query (all statuses)
    // and a status-write (dismiss/undo + accept's mark-Accepted). The shell wires these from this
    // Account's `brainDumpDraftRepository`. Defaulted to "no drafts" / no-op so the many shell tests build
    // without supplying them — the Inbox is simply empty.
    private val observeBrainDumpDrafts: () -> Flow<List<BrainDumpDraft>> = { flowOf(emptyList()) },
    private val upsertBrainDumpDraft: suspend (BrainDumpDraft) -> Unit = {},
    // #211: attach the retained brain-dump recording to the just-created Task on Inbox accept. Wired from
    // this Account's session (reads the on-device recording + writes a per-Task attachment copy). No-op
    // default for the many shell tests / non-brain-dump platforms.
    private val attachBrainDumpRecording: suspend (taskId: String, BrainDumpDraft) -> Unit = { _, _ -> },
    // The Activity Destination's reverse-chron ledger feed (#260). Wired from this Account's session;
    // defaulted to "no activity" so the many shell tests build without supplying it.
    private val observeActivity: () -> Flow<List<ActivityEntry>> = { flowOf(emptyList()) },
    // --- The server-mediated Assistant Destination (ADR-0040, #282) ---
    // The request/response client (availability / enablement / apply / conversations). AppScope, threaded
    // from the AppComponent. Defaulted to an INERT client (every call Unavailable) so the many shell tests
    // — and the Android/desktop hosts, whose Assistant Views are deferred — build without wiring it; only
    // the iOS host passes the real client, so the Assistant Destination appears only there in v1.
    private val assistantClient: AssistantClient = InertAssistantClient,
    // The per-Account on-device Conversation cache (the chat history source of truth). Defaulted to an inert
    // empty store so shell tests / non-iOS hosts build without it; the iOS host threads this Account's store.
    private val conversationStore: ConversationStore = InertConversationStore,
    // The SSE turn-stream seam — the iOS host bridges a Swift URLSession transport in; every other host (and
    // every test) leaves it the graceful no-op NONE (a turn surfaces "not available here", never hangs).
    private val assistantStream: AssistantStream = AssistantStream.NONE,
    // The AppScope connectivity signal the Assistant composer reads (online-only to extend a chat, ADR-0040).
    // Defaulted to assume-online so tests build unchanged; production threads the AppComponent's monitor.
    private val connectivity: Connectivity = AssumeOnlineConnectivity(),
    // The process-wide "Active Account's session is expired" flag (#297) the read surfaces banner off.
    // Defaulted to "not expired" so the many shell tests build without it; production threads the
    // AppComponent's `reauthRequests.sessionExpired` (set on a 401, cleared on the next 2xx).
    override val sessionExpired: StateFlow<Boolean> = MutableStateFlow(false),
) : MainShellComponent, ComponentContext by componentContext {

    // "Add subtask" on the Task detail: an online-only create of a child Task, derived from the same
    // [create] command seam the New surface uses (ADR-0016) so there's one create path, not two.
    private val createSubtask: suspend (TaskId, String) -> Unit = { parent, title ->
        create(
            com.circuitstitch.deferno.core.domain.command.CreateItem.Payload.Task(
                com.circuitstitch.deferno.core.network.dto.CreateTaskPayload(title = title, parentId = parent.value),
            ),
        )
    }

    // The Item-tree command menu's per-row Task state (#231): join each Task's working-state + pinned flag
    // with whether it's in today's plan (keyed by id) so the menu can label Pin↔Unpin / Add↔Remove-from-plan
    // and swap the kind-aware status block. Only Tasks appear (the status/Pin/plan writes are Task-only);
    // a non-Task tree row simply has no entry. Cold + offline-first (both reads are local Flows, ADR-0001).
    private val treeMenuStates: Flow<Map<String, TaskMenuState>> =
        combine(taskRepository.observeTasks(), planRepository.observePlan(today, timeZone)) { tasks, plan ->
            val planIds = plan.mapTo(HashSet(plan.size)) { it.id.value }
            tasks.associate { it.id.value to TaskMenuState(it.workingState, it.pinned, it.id.value in planIds) }
        }

    // The Inbox "accept" seam (ADR-0015 Inbox amendment): commit a draft as a real Task through the same
    // online-only create path the New surface uses (ADR-0016), then mark it Accepted so it leaves the
    // Ready queue and is never re-created (create isn't idempotent in v1, ADR-0016). Offline/Failed keep
    // the draft Ready for a retry — never a silent loss.
    private val acceptBrainDumpDraft: suspend (BrainDumpDraft) -> AcceptResult = { draft ->
        when (val result = create(draft.toCreatePayload(timeZone))) {
            is CommandResult.Accepted -> {
                // The Task is created. First, if a recording was retained for this brain dump, attach it to
                // the new Task (#211) — BEFORE the mark-Accepted below, whose reap may delete the retained
                // WAV once this is the recording's last Ready draft. Best-effort (runCatching): a failed
                // attach must never turn a created Task into an error. The created id is the create result's
                // itemId (the online create surfaces it, ADR-0016).
                result.itemId?.let { taskId ->
                    runCatching { attachBrainDumpRecording(taskId, draft) }
                        // Best-effort, but never silent: a swallowed attach is exactly why gh#223 shipped
                        // invisibly. Log on failure (the Task still reports Accepted — a failed attach is not
                        // a failed create).
                        .onFailure { Logger("InboxAccept").e(it) { "Brain-dump attach failed for task $taskId" } }
                }
                // Mark the draft Accepted so it leaves the Ready queue and isn't re-created. The local mark
                // is best-effort (runCatching): a failed mark leaves the draft Ready — it reappears, and
                // re-accepting then risks a duplicate, the residual non-idempotent-create gap that
                // client-supplied item ids close (ADR-0016 → #307). We still report Accepted because the
                // Task *was* created — never surface a scary error for a success, and never re-create on
                // this attempt.
                runCatching { upsertBrainDumpDraft(draft.copy(status = BrainDumpDraftStatus.Accepted)) }
                AcceptResult.Accepted
            }
            is CommandResult.Offline -> AcceptResult.Offline
            is CommandResult.Failed -> AcceptResult.Failed(result.message)
            // A pre-flight Rejected never happens for CreateItem, but be total — a gentle soft failure.
            is CommandResult.Rejected -> AcceptResult.Failed("Couldn't save this task.")
        }
    }

    // The rendered registry (ADR-0040): the conditionally-present Assistant row is inserted only once the Org
    // resolves to `entitled` (the availability fetch in init flips it on, see [destinationsFor]). Enum order
    // is preserved, so the entitled list is exactly Plan·Calendar·Tasks·Assistant·Inbox·Activity·Profile·Settings.
    private val _destinations = MutableStateFlow(destinationsFor(assistantEntitled = false))
    override val destinations: StateFlow<List<Destination>> = _destinations

    // The server-mediated Assistant wiring (ADR-0040, #282): org resolution + the SINGLE shared availability
    // gate + the enablement write-through, extracted (#284) so each Destination's wiring stays local. The gate
    // is the shared source the Assistant Destination and the Settings row both observe (they can't diverge).
    private val assistant = AssistantShellWiring(authRepository, assistantClient)

    override fun switchAccount(id: AccountId) = onSwitchAccount(id)

    override fun signOut() = output(MainShellComponent.Output.SignOutRequested)

    // Plain-data configs (serializer = null → no state restoration wired in v1, matching the feature
    // components). One per Destination; equality is what `bringToFront` matches to retain a child.
    private sealed interface Config {
        data object Plan : Config
        data object Calendar : Config
        data object Tasks : Config
        data object Assistant : Config
        data object Inbox : Config
        // The Activity ledger is a placeholder Destination for now (no slice yet) — see createChild.
        data object Activity : Config
        data object Profile : Config
        data object Settings : Config
    }

    private val navigation = StackNavigation<Config>()

    // The Plan Destination's tier-3 detail drill-down (ADR-0007 t3, #51): a Dashboard base + any drilled
    // Task Detail above it, rendered INSIDE the chrome (so the drawer stays live) — not the shell overlay
    // that used to yank the nav away. Owned here, not in feature/plan, so there's no feature→feature
    // dependency (ADR-0004 NiA: the shell is the composition layer that may see both slices).
    private sealed interface PlanConfig {
        data object Dashboard : PlanConfig
        data class Detail(val id: TaskId) : PlanConfig
    }

    private val planDetailNav = StackNavigation<PlanConfig>()

    override val stack: Value<ChildStack<*, MainShellComponent.DestinationChild>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = Config.Plan, // open into the Plan (design-principles.md)
            key = "DestinationStack",
            handleBackButton = false, // tier-1 back is routed via onBack(), not a global stack pop
            childFactory = ::createChild,
        )

    private val overlayNavigation = SlotNavigation<OverlayRoute>()

    override val overlay: Value<ChildSlot<*, MainShellComponent.OverlayChild>> =
        childSlot(
            source = overlayNavigation,
            serializer = null,
            key = "OverlaySlot",
            handleBackButton = false, // overlay back is routed via onBack() with precedence
            childFactory = ::createOverlay,
        )

    override fun selectDestination(destination: Destination) {
        // bringToFront reuses the child if this Destination is already on the stack (state-preserving),
        // otherwise creates it — the multiple-back-stack switch (ADR-0007 tier 1 / ADR-0008 G5).
        navigation.bringToFront(destination.toConfig())
    }

    override fun openOverlay(route: OverlayRoute) = overlayNavigation.activate(route)

    override fun dismissOverlay() = overlayNavigation.dismiss()

    override fun onBack(): Boolean {
        // An open overlay sits above the whole graph (Search/New, ADR-0015), so it dismisses first.
        if (overlay.value.child != null) {
            dismissOverlay()
            return true
        }
        val active = stack.value.active.instance
        if (active.handleInnerBack()) return true
        // Profile is a drill-down from Settings → Account (not a drawer Destination): back returns there,
        // not to the Plan home (#NN).
        if (active.destination == Destination.Profile) {
            navigation.bringToFront(Config.Settings)
            return true
        }
        // Nothing left inside the active Destination: from a non-home Destination, return to the Plan
        // home (its retained state is untouched); on the home Destination, let the platform exit.
        if (active.destination != Destination.Plan) {
            navigation.bringToFront(Config.Plan)
            return true
        }
        return false
    }

    private fun createChild(
        config: Config,
        childContext: ComponentContext,
    ): MainShellComponent.DestinationChild =
        when (config) {
            Config.Plan -> {
                // The Plan tier-3 stack: Dashboard at the base, a drilled Task Detail above it (ADR-0007
                // t3). A Plan tap pushes Detail; subtask/add-to-plan/show-steps are routed by
                // onPlanDetailOutput. The whole stack renders inside the chrome body, so the drawer stays.
                val planStack = childContext.childStack(
                    source = planDetailNav,
                    serializer = null,
                    initialConfiguration = PlanConfig.Dashboard,
                    key = "PlanStack",
                    handleBackButton = false, // Plan inner-back is routed via onBack(), like the other tiers
                    childFactory = { planConfig, ctx ->
                        when (planConfig) {
                            PlanConfig.Dashboard -> MainShellComponent.PlanChild.Dashboard(
                                DefaultPlanComponent(
                                    componentContext = ctx,
                                    planRepository = planRepository,
                                    date = today,
                                    tz = timeZone,
                                    output = { out ->
                                        when (out) {
                                            is PlanComponent.Output.OpenTask ->
                                                planDetailNav.navigate { it + PlanConfig.Detail(out.id) }
                                        }
                                    },
                                    coroutineContext = coroutineContext,
                                ),
                            )

                            is PlanConfig.Detail -> MainShellComponent.PlanChild.Detail(
                                DefaultTaskDetailComponent(
                                    componentContext = ctx,
                                    taskId = planConfig.id,
                                    taskRepository = taskRepository,
                                    output = ::onPlanDetailOutput,
                                    workingStateEditor = workingStateEditor,
                                    detailRepository = taskDetailRepository,
                                    createSubtask = createSubtask,
                                    setDeadline = setDeadline,
                                    setLabels = setLabels,
                                    delete = deleteTask,
                                    onDeviceAttachments = onDeviceAttachments,
                                    // The Account-scoped fold store: a subtask folded in the Plan-tap
                                    // detail matches the Tasks tree and survives restart (ADR-0034 dec. 4).
                                    foldStore = foldStore,
                                    coroutineContext = coroutineContext,
                                ),
                            )
                        }
                    },
                )
                MainShellComponent.DestinationChild.Plan(
                    stack = planStack,
                    // SKIE-facing mirror, tied to this Plan child's lifecycle (cancels on its destroy).
                    activeChild = planStack.asStateFlow(childContext.componentScope(coroutineContext)) {
                        it.active.instance
                    },
                    // Pop a drilled detail back toward the dashboard; not consumed at the dashboard base.
                    onBack = {
                        if (planStack.value.backStack.isNotEmpty()) {
                            planDetailNav.pop()
                            true
                        } else {
                            false
                        }
                    },
                )
            }

            Config.Calendar ->
                MainShellComponent.DestinationChild.Calendar(
                    DefaultCalendarComponent(
                        componentContext = childContext,
                        calendarRepository = calendarRepository,
                        occurrenceEditor = occurrenceEditor,
                        today = today,
                        tz = timeZone,
                        output = ::onCalendarOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Tasks ->
                MainShellComponent.DestinationChild.Tasks(
                    DefaultTasksComponent(
                        componentContext = childContext,
                        itemRepository = itemRepository,
                        foldStore = foldStore,
                        taskRepository = taskRepository,
                        output = ::onTasksOutput,
                        workingStateEditor = workingStateEditor,
                        definitionStateEditor = definitionStateEditor,
                        moveEditor = moveEditor,
                        shakeToUndoPreference = shakeToUndoPreference,
                        taskDetailRepository = taskDetailRepository,
                        createSubtask = createSubtask,
                        setDeadline = setDeadline,
                        setLabels = setLabels,
                        deleteTask = deleteTask,
                        onDeviceAttachments = onDeviceAttachments,
                        menuStates = treeMenuStates,
                        setPinned = setPinned,
                        addToPlan = addToPlan,
                        removeFromPlan = removeFromPlan,
                        coroutineContext = coroutineContext,
                    ),
                )

            // The Assistant Destination (ADR-0040, #282): the server-mediated chat. Built lazily on first
            // visit — by which point availability has resolved [assistant].orgId (the row is hidden until
            // entitled). The defensive null branch keeps createChild total on a stray select without an NPE.
            Config.Assistant -> assistant.orgId?.let { orgId ->
                MainShellComponent.DestinationChild.Assistant(
                    DefaultAssistantComponent(
                        componentContext = childContext,
                        orgId = orgId,
                        client = assistantClient,
                        store = conversationStore,
                        stream = assistantStream,
                        connectivity = connectivity,
                        // The SHARED availability gate + enablement write-through — the same source the Settings
                        // row reads, so enabling/disabling from either surface reflects in both (ADR-0040).
                        availability = assistant.availability,
                        setEnabled = assistant::setEnabled,
                        // After a confirmed proposal applies server-side, re-sync the affected items through
                        // the normal pull — NOT the outbox; here the server is the writer (ADR-0040).
                        resyncAfterApply = { taskRepository.refresh() },
                        coroutineContext = coroutineContext,
                    ),
                )
            } ?: MainShellComponent.DestinationChild.Placeholder(Destination.Assistant)

            Config.Inbox ->
                MainShellComponent.DestinationChild.Inbox(
                    DefaultInboxComponent(
                        componentContext = childContext,
                        observeDrafts = observeBrainDumpDrafts,
                        accept = acceptBrainDumpDraft,
                        upsert = upsertBrainDumpDraft,
                        coroutineContext = coroutineContext,
                    ),
                )

            // The Activity Destination (#260): the reverse-chron feed over this Account's offline-first
            // ledger, observed live so it re-emits as new changes land.
            Config.Activity ->
                MainShellComponent.DestinationChild.Activity(
                    DefaultActivityComponent(
                        componentContext = childContext,
                        observeActivity = observeActivity,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Profile ->
                MainShellComponent.DestinationChild.Profile(
                    DefaultProfileComponent(
                        componentContext = childContext,
                        authRepository = authRepository,
                        // Time zone moved into Profile (#72) — sourced from this Account's offline-first settings.
                        settingsRepository = settingsRepository,
                        account = account,
                        output = ::onProfileOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Settings ->
                MainShellComponent.DestinationChild.Settings(
                    DefaultSettingsComponent(
                        componentContext = childContext,
                        settingsRepository = settingsRepository,
                        settingsEditor = settingsEditor,
                        // The Security & 2FA category (#72 follow-through): the per-Account MFA/devices
                        // seam + this Account's own token id (marks "this device"; its revoke is withheld).
                        securityRepository = securityRepository,
                        activeTokenId = account.tokenId,
                        output = ::onSettingsOutput,
                        // The device-local speech-engine App setting (#93) + the device locale its
                        // availability is queried for — sourced from AppScope, not this Account's settings.
                        speechEngineCatalog = speechEngineCatalog,
                        locale = locale,
                        // The Agent inference-engine choice + entitlement gate (#150) — sourced from
                        // AppScope, device-local selection + per-Account entitlement, not synced settings.
                        inferenceEngineCatalog = inferenceEngineCatalog,
                        // The server-mediated Assistant enablement (#282, ADR-0040) — the Owner's persistent
                        // disable/withdraw-consent row, over the AppScope client + resolved org (iOS-only in v1).
                        assistantEnablement = assistant.enablement,
                        // The device-local storage-provider choice (#210) — sourced from AppScope, never synced.
                        storageProviderCatalog = storageProviderCatalog,
                        // On-device storage usage for the Storage read-out (#211) — this Account's recordings.
                        onDeviceStorageUsage = onDeviceStorageUsage,
                        // On-device Backup export (#313, ADR-0041) — the Settings export action builds the zip.
                        buildBackup = buildBackupZip,
                        // On-device Backup import (#314, ADR-0041) — the Settings import action restores the zip.
                        restoreBackup = importBackup,
                        // The Account-category switcher (#NN): the roster + the Active Account's id, from
                        // the AppScope AccountManager (this Main shell is keyed to `account`).
                        accounts = accounts,
                        activeAccountId = account.id,
                        // The device-local "keep brain-dump recordings" choice (#211) — AppScope, never synced.
                        keepBrainDumpRecordingsPreference = keepBrainDumpRecordingsPreference,
                        // The device-local "Brain dump notifications" opt-in (#266/#271) — AppScope, never synced.
                        brainDumpNotificationPreference = brainDumpNotificationPreference,
                        // The device-local shake-to-undo choice (#230) — AppScope, never synced.
                        shakeToUndoPreference = shakeToUndoPreference,
                        coroutineContext = coroutineContext,
                    ),
                )
        }

    // Scope for the shell-owned overlay work (the New create dispatch, #71). Tied to the injected
    // coroutineContext so a test drives it on its own scheduler.
    private val overlayScope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())

    // SKIE-facing mirrors of the navigation Values (see the interface). overlayScope outlives the shell,
    // and the source Values are shell-owned, so the subscriptions need no separate teardown.
    override val activeDestination: StateFlow<MainShellComponent.DestinationChild> =
        stack.asStateFlow(overlayScope) { it.active.instance }
    override val activeOverlay: StateFlow<MainShellComponent.OverlayChild?> =
        overlay.asStateFlow(overlayScope) { it.child?.instance }

    // Bumped on each shell resume to re-read the drafts (cross-driver: the brain-dump worker writes Ready
    // drafts via its OWN SQLDelight driver, so the UI driver's live query doesn't see them — a fresh
    // selectAll on resume does). Same-driver writes here (accept/dismiss) still update the badge live.
    private val badgeResume = MutableStateFlow(0)

    // The Inbox nav badge count (ADR-0015 Inbox amendment): the number of Ready drafts, observed at shell
    // level so the badge is live even before the Inbox Destination is first visited. WhileSubscribed — the
    // chrome's drawer is the only collector.
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest — re-subscribe the count query on each resume.
    override val inboxReadyCount: StateFlow<Int> =
        badgeResume
            .flatMapLatest { observeBrainDumpDrafts() }
            .map { drafts -> drafts.count { it.status == BrainDumpDraftStatus.Ready } }
            .stateIn(overlayScope, SharingStarted.WhileSubscribed(5_000L), 0)

    init {
        lifecycle.doOnResume { badgeResume.value++ }
        // Resolve the active Org + the Assistant availability gate (ADR-0040) before the nav suite settles:
        // the conditionally-present Assistant row is revealed only once availability resolves to `entitled`.
        // Offline / failed / no real client (Android/desktop/tests) leaves the row absent — the Assistant is
        // online-only anyway. The one fetch fills the SHARED [assistant].availability both surfaces observe.
        overlayScope.launch {
            assistant.refresh()
            if (assistant.availability.value?.entitled == true) {
                _destinations.value = destinationsFor(assistantEntitled = true)
            }
        }
    }

    // ----- Adaptive top-bar chrome (Cand 1): one bar for every in-chrome surface, computed here so the
    // per-screen headers go away and the buttons stop coming and going arbitrarily. -----

    /** Bridge a Decompose [Value] to a [Flow] so chrome reacts to nav (and nested-nav / detail) changes. */
    private fun <T : Any> Value<T>.asFlow(): Flow<T> = callbackFlow {
        val cancellation = subscribe { trySend(it) }
        awaitClose { cancellation.cancel() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val chrome: StateFlow<ChromeSpec> =
        stack.asFlow()
            .flatMapLatest { st -> chromeFor(st.active.instance) }
            .stateIn(overlayScope, SharingStarted.WhileSubscribed(5_000L), rootChrome("", ChromeTitle.None))

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun chromeFor(active: MainShellComponent.DestinationChild): Flow<ChromeSpec> =
        when (active) {
            // Plan is a tier-3 stack: the dashboard root ("Today") or a drilled Task detail (its title).
            is MainShellComponent.DestinationChild.Plan ->
                active.stack.asFlow().flatMapLatest { ps ->
                    when (val child = ps.active.instance) {
                        // No bar title — the Plan dashboard body carries the big "Today" header itself,
                        // so a bar title would duplicate it (#260 chrome restyle). Just ☰ + Refresh.
                        is MainShellComponent.PlanChild.Dashboard ->
                            flowOf(rootChrome(ChromeTitle.None, onRefresh = child.component::onRefresh))
                        is MainShellComponent.PlanChild.Detail ->
                            child.component.state.map { s ->
                                val t = s.task?.title
                                drilledChrome(if (t != null) ChromeTitle.Verbatim(t) else ChromeTitle.TaskFallback)
                            }
                    }
                }

            // Tasks is the multi-pane workspace (ADR-0007 t2): its co-resident panes carry their own
            // headers (title · back · refresh), so the shell bar shows only the global actions here — no
            // title — and Tasks needs no View change. The single-pane Destinations title the bar instead.
            // Drop the capture actions (→ no FAB pair) while the Item tree is in move mode: the FABs sit
            // bottom-centre now and would cover the modal move bar's ↑↓‹›/Done controls otherwise.
            is MainShellComponent.DestinationChild.Tasks ->
                active.component.tree.state.map { rootChrome(ChromeTitle.None, capture = it.moveMode == null) }

            // Settings is a tier-3 stack: the category list ("Settings") or a drilled category (its title).
            is MainShellComponent.DestinationChild.Settings ->
                active.component.stack.asFlow().map { ss ->
                    when (val child = ss.active.instance) {
                        is SettingsComponent.SettingsChild.List ->
                            rootChrome(ChromeTitle.ForDestination(Destination.Settings))
                        is SettingsComponent.SettingsChild.Detail ->
                            drilledChrome(ChromeTitle.ForSettingsCategory(child.category))
                    }
                }

            // The Calendar New pre-dates to its selected day (#74) — wire that as this root's New handler.
            is MainShellComponent.DestinationChild.Calendar ->
                flowOf(rootChrome(ChromeTitle.ForDestination(Destination.Calendar), onNew = active.component::onNewForSelectedDay))

            // The Assistant chat (ADR-0040): on iOS the SwiftUI View owns its own chrome; the shared bar is
            // only rendered on the deferred Android/desktop Views, so a plain titled root bar suffices.
            is MainShellComponent.DestinationChild.Assistant ->
                flowOf(rootChrome(ChromeTitle.ForDestination(Destination.Assistant)))
            is MainShellComponent.DestinationChild.Inbox ->
                flowOf(rootChrome(ChromeTitle.ForDestination(Destination.Inbox)))
            // Profile is a drill-down from Settings → Account (no drawer row): ← back, returning to Settings.
            is MainShellComponent.DestinationChild.Profile ->
                flowOf(drilledChrome(ChromeTitle.ForDestination(Destination.Profile)))
            is MainShellComponent.DestinationChild.Activity ->
                flowOf(rootChrome(ChromeTitle.ForDestination(Destination.Activity)))
            is MainShellComponent.DestinationChild.Placeholder ->
                flowOf(rootChrome(ChromeTitle.ForDestination(active.destination)))
        }

    /** A Destination-root chrome: ☰ menu + title + a per-screen Refresh (if any) and the global create
     *  actions (Brain dump, New). New defaults to the undated overlay; the Calendar root pre-dates it. */
    private fun rootChrome(
        titleSpec: ChromeTitle,
        onRefresh: (() -> Unit)? = null,
        onNew: () -> Unit = { openOverlay(OverlayRoute.New()) },
        capture: Boolean = true,
    ): ChromeSpec = ChromeSpec(
        titleSpec = titleSpec,
        drilled = false,
        actions = buildList {
            if (onRefresh != null) add(ChromeAction(ChromeActionKind.Refresh, onRefresh))
            // The capture pair (Brain dump + New) — the FAB pair. Suppressed where a modal bottom bar owns
            // the bottom of the screen (the Tasks move mode), so the FABs don't cover its controls.
            if (capture) {
                add(ChromeAction(ChromeActionKind.BrainDump) { openOverlay(OverlayRoute.BrainDump) })
                add(ChromeAction(ChromeActionKind.New, onNew))
            }
        },
    )

    /** A drilled-detail chrome: ← back + the detail's own title, no create actions (those belong to roots). */
    private fun drilledChrome(titleSpec: ChromeTitle): ChromeSpec =
        ChromeSpec(titleSpec = titleSpec, drilled = true)

    private fun createOverlay(
        route: OverlayRoute,
        childContext: ComponentContext,
    ): MainShellComponent.OverlayChild =
        when (route) {
            OverlayRoute.Placeholder -> MainShellComponent.OverlayChild.Placeholder

            is OverlayRoute.Search ->
                MainShellComponent.OverlayChild.Search(
                    DefaultSearchComponent(
                        componentContext = childContext,
                        searchTasks = searchTasks,
                        output = ::onSearchOutput,
                        // A deep-link's pre-applied filter/sort (#311) — null for the plain ⌕ overlay.
                        initialSeed = route.seed,
                        // The Search overlay sits above the chrome, so the shell-level banner can't show
                        // over it — feed the same flag in so the overlay can surface "Session expired" (#297).
                        sessionExpired = sessionExpired,
                        coroutineContext = coroutineContext,
                    ),
                )

            is OverlayRoute.New -> MainShellComponent.OverlayChild.New(
                DefaultNewComponent(
                    create = create,
                    onCreated = ::dismissOverlay,
                    launch = { block -> overlayScope.launch { block() } },
                    tz = timeZone,
                    initialDate = route.date, // pre-date the form (the Calendar FAB, #74)
                    // Dictation (#92): the mic drives the AppScope SpeechToText on the overlay scope.
                    speech = speechToText,
                    locale = locale,
                    dictationScope = overlayScope,
                    // The foreclosed-permission deep-link (#120), routed through to the host.
                    onOpenDictationPermissionSettings = onOpenDictationPermissionSettings,
                ),
            )

            OverlayRoute.Feedback -> MainShellComponent.OverlayChild.Feedback(
                DefaultFeedbackComponent(
                    repository = feedbackRepository,
                    onDone = ::dismissOverlay,
                    launch = { block -> overlayScope.launch { block() } },
                ),
            )

            // Brain dump (ADR-0027/#150, Stage 4): record the mic to a WAV and hand it to the background
            // worker on Stop — transcription + extraction happen there and the proposed drafts land in the
            // Inbox (no inline review). The recorder seam is Android-only; desktop leaves it inert. The
            // injected today/timeZone give the worker the date context for relative deadlines ("tomorrow").
            OverlayRoute.BrainDump -> {
                val brainDump = DefaultBrainDumpComponent(
                    record = { recordBrainDump(today, timeZone) },
                    onDone = ::dismissOverlay,
                    scope = overlayScope,
                    onOpenDictationPermissionSettings = onOpenDictationPermissionSettings,
                )
                // The recorder runs until cancelled (it does not self-terminate), so stop the mic when the
                // overlay is torn down by ANY path — Close, system-back, or an account switch — since
                // overlayScope outlives this child. A real take is still handed off; an empty one is dropped.
                childContext.lifecycle.doOnDestroy(brainDump::cancelCapture)
                MainShellComponent.OverlayChild.BrainDump(brainDump)
            }

            // Breakdown (Deferno#525): the impediment "what's stopping you?" flow over one stuck item,
            // opened from item detail. The deterministic engine + classifier are shared Kotlin driving the
            // Android/desktop Compose chat (iOS renders its SwiftUI twin over the same holder); this holder
            // observes the target and exposes the offline-first moves behind CommandBreakdownActions
            // (reusing the same create / working-state / add-to-plan seams as every other write — so a
            // captured subtask or a drop rides the outbox like any edit).
            is OverlayRoute.Breakdown -> {
                val breakdown = DefaultBreakdownComponent(
                    componentContext = childContext,
                    taskId = route.taskId,
                    taskRepository = taskRepository,
                    actions = CommandBreakdownActions(
                        createItem = create,
                        workingStateEditor = workingStateEditor,
                        addToPlanFn = addToPlan,
                    ),
                    onCloseRequested = ::dismissOverlay,
                    coroutineContext = coroutineContext,
                )
                MainShellComponent.OverlayChild.Breakdown(breakdown)
            }
        }

    private fun onSearchOutput(output: SearchComponent.Output) {
        when (output) {
            // A result tap opens that Task in the Tasks Destination — dismiss the overlay, switch
            // laterally, then route the open through the list's public intent (the same path a real
            // list tap takes, mirroring onPlanOutput).
            is SearchComponent.Output.OpenTask -> {
                dismissOverlay()
                navigation.bringToFront(Config.Tasks)
                val tasks = stack.value.active.instance as MainShellComponent.DestinationChild.Tasks
                tasks.component.tree.onOpenDetail(output.id.value, ItemKind.Task)
            }
            SearchComponent.Output.Dismissed -> dismissOverlay()
        }
    }

    /**
     * The Plan Destination's Task-detail intents (#51), routed through Plan's own tier-3 stack instead of a
     * shell overlay: close pops a level; a subtask drills one deeper (a real back stack); add-to-plan
     * bubbles to the host; "show steps" leaves Plan's detail and opens the Task in the full Tasks workspace.
     */
    private fun onPlanDetailOutput(output: TaskDetailComponent.Output) {
        when (output) {
            TaskDetailComponent.Output.Closed -> planDetailNav.pop()
            is TaskDetailComponent.Output.AddToPlanRequested ->
                this.output(MainShellComponent.Output.AddToPlanRequested(output.id))
            // Drilling into a subtask pushes one level deeper on Plan's detail stack (back pops it).
            is TaskDetailComponent.Output.SubtaskSelected ->
                planDetailNav.navigate { it + PlanConfig.Detail(output.id) }
            // "Break this down" (Deferno#525) opens the Breakdown overlay over the stuck item.
            is TaskDetailComponent.Output.BreakdownRequested ->
                openOverlay(OverlayRoute.Breakdown(output.id.value))
        }
    }

    private fun onCalendarOutput(output: CalendarComponent.Output) {
        when (output) {
            // The Calendar FAB opens New pre-dated to the selected day (#74 AC) — push the New overlay
            // carrying that date, reusing the same overlay primitive every create surface rides.
            is CalendarComponent.Output.CreateForDay -> openOverlay(OverlayRoute.New(output.date))
        }
    }

    private fun onTasksOutput(output: TasksComponent.Output) {
        when (output) {
            is TasksComponent.Output.AddToPlanRequested ->
                this.output(MainShellComponent.Output.AddToPlanRequested(output.id))
            // "Break this down" from the Tasks workspace detail (Deferno#525) opens the Breakdown overlay.
            is TasksComponent.Output.BreakdownRequested ->
                openOverlay(OverlayRoute.Breakdown(output.id.value))
        }
    }

    private fun onProfileOutput(output: ProfileComponent.Output) {
        when (output) {
            // Sign out is a host concern (it crosses the Account-isolation boundary, ADR-0002): re-emit
            // for the RootComponent to secure-wipe the Active Account and return to the Auth shell.
            ProfileComponent.Output.SignOutRequested ->
                this.output(MainShellComponent.Output.SignOutRequested)
        }
    }

    private fun onSettingsOutput(output: SettingsComponent.Output) {
        when (output) {
            // OS / web deep-links cross the app boundary (an Android Intent), so they land at the host (#72).
            SettingsComponent.Output.OpenOsAppSettings ->
                this.output(MainShellComponent.Output.OpenOsAppSettings)
            // Export/import has no client endpoint at v0.1, so it stays a reachable web action: re-emit
            // for the host to deep-link the web app (AC #3, ADR-0015).
            SettingsComponent.Output.OpenDataExportImport ->
                this.output(MainShellComponent.Output.OpenDataExportImport)
            // Feedback is now an in-app surface (#375): open the Feedback overlay over the foreground
            // Destination, the same overlay primitive Search/New ride — no web round-trip.
            SettingsComponent.Output.OpenSubmitFeedback ->
                openOverlay(OverlayRoute.Feedback)
            SettingsComponent.Output.OpenConsoleUrl ->
                this.output(MainShellComponent.Output.OpenConsoleUrl)
            // "View profile" is a lateral switch within the shell — handle it here (the shell owns the
            // Destination graph), and also surface it for the host (parity with the other Outputs).
            SettingsComponent.Output.OpenProfile -> {
                navigation.bringToFront(Config.Profile)
                this.output(MainShellComponent.Output.OpenProfile)
            }
            // A Storage recording tap (#211): open its owning Task in the Tasks Destination — switch laterally,
            // then route through the tree's public open intent (mirroring onSearchOutput).
            is SettingsComponent.Output.OpenTask -> {
                navigation.bringToFront(Config.Tasks)
                val tasks = stack.value.active.instance as MainShellComponent.DestinationChild.Tasks
                tasks.component.tree.onOpenDetail(output.taskId, ItemKind.Task)
            }
            // An un-triaged recording placeholder has no owning Task yet — go to the Inbox where triage clears it.
            SettingsComponent.Output.OpenInbox -> navigation.bringToFront(Config.Inbox)
            // The "biggest attachments" deep-link (#311): open Search seeded with the attachment filter +
            // size sort, so the person lands on their largest-attachment items.
            SettingsComponent.Output.OpenBiggestAttachments ->
                openOverlay(OverlayRoute.Search(SearchSeed(hasAttachment = true, sort = SearchSort.AttachmentSizeDesc)))
            // The Account switcher (#NN): switching re-keys the Main shell (the same callback the drawer
            // switcher uses); add-account + sign-out cross the Account-isolation boundary, so they land at
            // the root (ADR-0002) — the same Output sign-out has always used.
            is SettingsComponent.Output.SwitchAccount -> onSwitchAccount(output.id)
            SettingsComponent.Output.AddAccount -> this.output(MainShellComponent.Output.AddAccountRequested)
            SettingsComponent.Output.SignOut -> this.output(MainShellComponent.Output.SignOutRequested)
        }
    }

    private fun Destination.toConfig(): Config =
        when (this) {
            Destination.Plan -> Config.Plan
            Destination.Calendar -> Config.Calendar
            Destination.Tasks -> Config.Tasks
            Destination.Assistant -> Config.Assistant
            Destination.Inbox -> Config.Inbox
            Destination.Activity -> Config.Activity
            Destination.Profile -> Config.Profile
            Destination.Settings -> Config.Settings
        }

    /**
     * Tier-2/tier-3 back for the active Destination, using only its public intents. Single-pane and
     * placeholder Destinations have no inner back; Tasks delegates to [dismissForegroundPane].
     */
    private fun MainShellComponent.DestinationChild.handleInnerBack(): Boolean =
        when (this) {
            is MainShellComponent.DestinationChild.Plan -> this.onBack()
            // Calendar is single-pane (no tier-2/tier-3): nothing to dismiss inside it.
            is MainShellComponent.DestinationChild.Calendar -> false
            is MainShellComponent.DestinationChild.Tasks -> component.dismissForegroundPane()
            // The Assistant chat's switcher / new-conversation are component-internal state, not shell-poppable
            // Decompose nav — there is nothing for the shell's back to dismiss inside it.
            is MainShellComponent.DestinationChild.Assistant -> false
            // The Inbox is a single-pane list (no tier-2/tier-3): nothing to dismiss inside it.
            is MainShellComponent.DestinationChild.Inbox -> false
            is MainShellComponent.DestinationChild.Profile -> false
            // Settings is a tier-3 drill-down: back pops an open category detail to the list first (#72).
            is MainShellComponent.DestinationChild.Settings -> component.onBack()
            // Activity is a single-pane read-only feed: nothing to dismiss inside it.
            is MainShellComponent.DestinationChild.Activity -> false
            is MainShellComponent.DestinationChild.Placeholder -> false
        }
}

/**
 * The rendered Destination registry for a given entitlement (ADR-0040, #284): enum order, with the
 * conditionally-present [Destination.Assistant] kept only when [assistantEntitled]. Expressed as "insert
 * Assistant into the filtered list" (not `Destination.entries` wholesale) so a future second conditional
 * Destination can't silently ride along on the entitled flip — it would need its own clause here.
 */
private fun destinationsFor(assistantEntitled: Boolean): List<Destination> =
    Destination.entries.filter {
        // Profile is reached by drilling from Settings → Account (a ← back detail), not a drawer row (#NN).
        it != Destination.Profile &&
            (it != Destination.Assistant || assistantEntitled)
    }

/**
 * Dismiss the Tasks Destination's open detail (ADR-0034). The Item [TasksComponent.tree] is the
 * always-present primary pane, so the only dismissible co-resident pane is the detail: back closes it and
 * reveals the tree beneath; at the bare tree, back is not consumed (the shell's outer back takes over).
 */
private fun TasksComponent.dismissForegroundPane(): Boolean {
    detail.value.child?.instance?.let { it.onCloseClicked(); return true }
    return false
}

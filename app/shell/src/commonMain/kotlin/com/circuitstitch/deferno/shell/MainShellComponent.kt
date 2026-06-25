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
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchResult
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
import com.circuitstitch.deferno.feature.tasks.TasksComponent
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

/**
 * The **Main shell** (ADR-0013): the post-[[Account]] surface that hosts the **Destination graph**.
 * It exposes the [Destination] registry ([destinations]) the View renders as a nav suite, the active
 * Destination as a Decompose [ChildStack], and a single shell-level **overlay route** ([overlay]) that
 * sits above the whole graph (ADR-0015).
 *
 * Tier-1 switching ([selectDestination]) is **lateral and state-preserving — multiple back stacks**
 * (ADR-0007 tier 1 / ADR-0008 G5): each Destination's component (and its own tier-2 panes / tier-3
 * drill-downs) is **retained** while another Destination is foreground, so leaving Tasks for Plan and
 * returning restores Tasks exactly. This is realized with Decompose `bringToFront`, which reuses the
 * existing child for a Destination already on the stack rather than recreating it. Destinations are
 * created lazily (only on first visit), so the registry scales to a dozen-plus without eager cost.
 *
 * The **overlay route** ([openOverlay] / [dismissOverlay]) is the shared mechanism Search and New will
 * reuse (ADR-0015): a route pushed *above* the foreground Destination and dismissed back to origin,
 * leaving the Destination's retained state untouched. v1 ships only a trivial [OverlayRoute.Placeholder]
 * so the mechanism — render position and back precedence — exists and is exercised; the real routes
 * land with #71 (New) and #73 (Search).
 *
 * The shell also routes the cross-feature intents the Destinations emit (a Plan tap opens that Task in
 * an overlay above the dashboard; a Tasks "add to plan" and a Profile "sign out" bubble up via [Output]
 * for the host to apply against the Active Account — the same Output-up routing the demo host owned).
 */
interface MainShellComponent {
    /**
     * The ordered Destination registry the nav suite renders — not a fixed count, and **not constant**:
     * the conditionally-present [Destination.Assistant] is omitted until the Org is `entitled` (ADR-0040),
     * so this is a [StateFlow] the View observes (the Assistant row appears once availability resolves).
     */
    val destinations: StateFlow<List<Destination>>

    /** The foreground Destination + the retained, state-preserving back stack of visited Destinations. */
    val stack: Value<ChildStack<*, DestinationChild>>

    /**
     * The shell-level overlay route, or empty when none is open (ADR-0015). The View renders the
     * [OverlayChild] above the foreground Destination; [onBack] dismisses it first.
     */
    val overlay: Value<ChildSlot<*, OverlayChild>>

    /**
     * The active Destination + open overlay, mirrored as [StateFlow]s for the SwiftUI Views to observe
     * via SKIE (which bridges `StateFlow` + the sealed children → Swift enums, but not Decompose's
     * [Value]/[ChildStack]/[ChildSlot]). The Compose/Android side keeps observing [stack]/[overlay].
     */
    val activeDestination: StateFlow<DestinationChild>
    val activeOverlay: StateFlow<OverlayChild?>

    /** Switch foreground Destination laterally, preserving every Destination's retained state. */
    fun selectDestination(destination: Destination)

    /** Push [route] as a shell-level overlay above the foreground Destination (ADR-0015). */
    fun openOverlay(route: OverlayRoute)

    /** Dismiss the open overlay back to the foreground Destination (a no-op if none is open). */
    fun dismissOverlay()

    /**
     * Route Android system-back within the Main shell: dismiss an open [overlay] first, then let the
     * active Destination dismiss its own tier-2/tier-3 state, then fall back from a non-home Destination
     * to the [Destination.Plan] home. Returns whether back was consumed (`false` → the host lets the
     * platform exit the scene).
     */
    fun onBack(): Boolean

    /** The Accounts on this device + the Active one, for the in-shell account switcher (ADR-0014). */
    val accounts: StateFlow<List<Account>>
    val activeAccount: StateFlow<Account?>

    /**
     * The count of **Ready** Brain dump drafts awaiting triage — the nav badge on the [Destination.Inbox]
     * row ("empty"/[n], ADR-0015 Inbox amendment). Observed at shell level so the badge shows even before
     * the (lazily-built) Inbox Destination is first visited.
     */
    val inboxReadyCount: StateFlow<Int>

    /**
     * The adaptive top-bar [ChromeSpec] for the foreground **in-chrome** surface (Cand 1) — one bar,
     * computed in the shell from the active Destination + its tier-3 drill-down. The View renders it, so
     * the per-screen headers are gone and the header buttons no longer "come and go" arbitrarily.
     */
    val chrome: StateFlow<ChromeSpec>

    /** Switch the Active Account — re-keys the shell for the new Account, no re-auth (ADR-0002/0012). */
    fun switchAccount(id: AccountId)

    /**
     * Request sign-out of the Active Account from a **shell-chrome** affordance (the desktop Account
     * menu, ADR-0017) — emits [Output.SignOutRequested] for the host/root to secure-wipe the Account
     * (ADR-0009/0012). It is the same intent the Profile Destination's sign-out button raises, surfaced
     * at shell level so chrome outside the Destination graph (a menu bar) can raise it too.
     */
    fun signOut()

    /** A live Destination instance, tagged with which [Destination] it is so the View can render it. */
    sealed interface DestinationChild {
        val destination: Destination

        /**
         * The Plan Destination — now a tier-3 drill-down (ADR-0007 t3, #51): [stack] is the dashboard at
         * the base and any drilled-in single-Task [detail][PlanChild.Detail] above it, rendered INSIDE the
         * shell chrome (the drawer stays live), not as a shell overlay above it. [onBack] pops an open
         * detail toward the dashboard, returning whether it consumed the back.
         */
        class Plan(
            val stack: Value<ChildStack<*, PlanChild>>,
            /** [stack]'s active child mirrored for SwiftUI to observe via SKIE (see [activeDestination]). */
            val activeChild: StateFlow<PlanChild>,
            val onBack: () -> Boolean,
        ) : DestinationChild {
            override val destination: Destination = Destination.Plan
        }

        /** The Calendar Destination (#74): a single-pane month grid + day agenda over Occurrences. */
        class Calendar(val component: CalendarComponent) : DestinationChild {
            override val destination: Destination = Destination.Calendar
        }

        class Tasks(val component: TasksComponent) : DestinationChild {
            override val destination: Destination = Destination.Tasks
        }

        /**
         * The Assistant Destination (ADR-0040, #282): the server-mediated conversational AI chat. Holds
         * the shared [AssistantComponent] state machine; the iOS SwiftUI View renders it (the
         * Android/desktop Compose Views are deferred — they show a placeholder body). Only built when the
         * Org is `entitled`, so it is reached only on iOS in v1, where the client is wired.
         */
        class Assistant(val component: AssistantComponent) : DestinationChild {
            override val destination: Destination = Destination.Assistant
        }

        /** The Inbox Destination (ADR-0015 amendment): the triage queue for persisted Brain dump drafts. */
        class Inbox(val component: InboxComponent) : DestinationChild {
            override val destination: Destination = Destination.Inbox
        }

        class Profile(val component: ProfileComponent) : DestinationChild {
            override val destination: Destination = Destination.Profile
        }

        /** The Settings tier-3 drill-down (#72): a category list → per-category detail (ADR-0007 t3). */
        class Settings(val component: SettingsComponent) : DestinationChild {
            override val destination: Destination = Destination.Settings
        }

        /** The Activity Destination (#260): a reverse-chronological feed of the offline-first ledger. */
        class Activity(val component: ActivityComponent) : DestinationChild {
            override val destination: Destination = Destination.Activity
        }

        /**
         * A Destination whose own slice isn't built yet (Calendar #74) — a logic-less child the View
         * renders as a placeholder body. It is still a real tier-1 Destination with its own retained
         * back-stack entry, so it drops in its slice later with no structural change.
         */
        class Placeholder(override val destination: Destination) : DestinationChild
    }

    /**
     * The Plan Destination's tier-3 children (ADR-0007 t3): the dashboard at the base of
     * [DestinationChild.Plan.stack], and a drilled-in single-Task [Detail] above it. Detail reuses the
     * Tasks slice's [TaskDetailComponent] — the shell composes both slices (ADR-0004), so Plan needs no
     * feature→feature dependency — so it hydrates + edits identically to the Tasks Destination's detail.
     */
    sealed interface PlanChild {
        class Dashboard(val component: PlanComponent) : PlanChild
        class Detail(val component: TaskDetailComponent) : PlanChild
    }

    /** A shell-level overlay instance the View renders above the foreground Destination (ADR-0015). */
    sealed interface OverlayChild {
        /** The v1 stand-in (both Search #73 and New #71 are real routes over the same primitive). */
        data object Placeholder : OverlayChild

        /** The global Search overlay (#73): a real route over the same overlay primitive. */
        class Search(val component: SearchComponent) : OverlayChild

        /** The New create surface (#71): the kind picker + per-kind form, online-only create (ADR-0016). */
        class New(val component: NewComponent) : OverlayChild

        /** The in-app Help → Feedback surface (#375): comment + attachments, online-only submit. */
        class Feedback(val component: FeedbackComponent) : OverlayChild

        /**
         * The **Brain dump** surface (ADR-0027, #150; Stage 4): a voice recorder. It records the mic to a
         * WAV and hands it to the background worker on Stop; transcription + extraction run there and the
         * proposed drafts land in the [Destination.Inbox] for review (no inline review in the overlay).
         */
        class BrainDump(val component: BrainDumpComponent) : OverlayChild
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent, re-emitted for the host (the Plan write isn't the shell's job). */
        data class AddToPlanRequested(val id: TaskId) : Output

        /** A Profile "sign out" intent — the host secure-wipes the Active Account (ADR-0009/0012). */
        data object SignOutRequested : Output

        /** A Settings "App Permissions" tap — the host deep-links to the OS app-settings screen (#72). */
        data object OpenOsAppSettings : Output

        /** A Settings "Data & Privacy → export/import" tap — the host deep-links the web app (#72, AC #3). */
        data object OpenDataExportImport : Output

        /** A Settings "Security & 2FA" tap — the host opens the Zitadel console URL when present (#72). */
        data object OpenConsoleUrl : Output

        /** A Settings "Account → View profile" tap — switch laterally to the Profile Destination (#72). */
        data object OpenProfile : Output
    }
}

/** The shell-level overlay routes (ADR-0015): the v1 [Placeholder], plus [Search] (#73) and [New] (#71). */
sealed interface OverlayRoute {
    /** The trivial v1 placeholder so the overlay mechanism is wired and testable. */
    data object Placeholder : OverlayRoute

    /** The global Search route (#73) — launched from the ⌕ in any Destination app bar. */
    data object Search : OverlayRoute

    /**
     * The New create surface (#71): the FAB pushes this above the foreground Destination. [date]
     * pre-dates the form to a chosen day (the Calendar FAB, #74) — `null` opens an undated form.
     */
    data class New(val date: LocalDate? = null) : OverlayRoute

    /** The in-app Help → Feedback surface (#375), opened from Settings → Help & Feedback. */
    data object Feedback : OverlayRoute

    /** The **Brain dump** surface (ADR-0027), opened from the shell top bar's voice_chat action. */
    data object BrainDump : OverlayRoute
}

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
    // The Task detail's on-device attachment seam (#211), threaded into the Tasks destination + the
    // TaskDetail overlay. Defaults to the empty NONE so the many shell tests build without it; production
    // threads this Account's seam from the session.
    private val onDeviceAttachments: OnDeviceAttachments = OnDeviceAttachments.NONE,
    // The global-search seam (#73): a one-shot, online-only pull the Search overlay drives. Defaults
    // to "no results" so tests that don't exercise Search build without supplying it.
    private val searchTasks: SearchTasks = SearchTasks { _ -> TaskSearchResult.Success(emptyList()) },
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

    // The rendered registry (ADR-0040): the conditionally-present Assistant row is omitted until the Org
    // resolves to `entitled` (the availability fetch in init flips it on). Enum order is preserved, so the
    // entitled list is exactly Plan·Calendar·Tasks·Assistant·Inbox·Activity·Profile·Settings.
    private val destinationsWithoutAssistant = Destination.entries.filterNot { it == Destination.Assistant }
    private val _destinations = MutableStateFlow(destinationsWithoutAssistant)
    override val destinations: StateFlow<List<Destination>> = _destinations

    // The Org the Assistant client + component are scoped to (the active User's personal org in v1, ADR-0040).
    // Resolved async in init alongside the availability gate; non-null by the time the Assistant row is shown
    // (the row only appears once availability — which needs this id — has resolved to entitled).
    private var assistantOrgId: OrgId? = null

    /** The active User's personal org (ADR-0040), resolved once + cached; null only when unauthenticated. */
    private suspend fun resolveAssistantOrgId(): OrgId? =
        assistantOrgId ?: (authRepository.loadMe() as? MeResult.Authenticated)?.user?.personalOrgId
            ?.also { assistantOrgId = it }

    // The Settings Assistant-enablement seam (ADR-0040, #282): the AppScope request/response client + the
    // resolved personal org, gated server-side on entitlement. Threaded into the Settings Destination. Reads
    // the org lazily (resolving it if init's gate fetch hasn't yet), so the Settings row works even if opened
    // early; on the Android/desktop hosts the inert client makes every call Unavailable → the row stays hidden.
    private val assistantEnablement = object : AssistantEnablement {
        override suspend fun load(): AssistantAvailability? {
            val org = resolveAssistantOrgId() ?: return null
            return (assistantClient.availability(org) as? RemoteSnapshot.Available)?.value
        }

        override suspend fun setEnabled(enabled: Boolean): AssistantAvailability? {
            val org = resolveAssistantOrgId() ?: return null
            return (assistantClient.setEnablement(org, enabled) as? RemoteSnapshot.Available)?.value
        }
    }

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
                        moveEditor = moveEditor,
                        shakeToUndoPreference = shakeToUndoPreference,
                        taskDetailRepository = taskDetailRepository,
                        createSubtask = createSubtask,
                        setDeadline = setDeadline,
                        setLabels = setLabels,
                        deleteTask = deleteTask,
                        onDeviceAttachments = onDeviceAttachments,
                        coroutineContext = coroutineContext,
                    ),
                )

            // The Assistant Destination (ADR-0040, #282): the server-mediated chat. Built lazily on first
            // visit — by which point availability has resolved [assistantOrgId] (the row is hidden until
            // entitled). The defensive null branch keeps createChild total on a stray select without an NPE.
            Config.Assistant -> assistantOrgId?.let { orgId ->
                MainShellComponent.DestinationChild.Assistant(
                    DefaultAssistantComponent(
                        componentContext = childContext,
                        orgId = orgId,
                        client = assistantClient,
                        store = conversationStore,
                        stream = assistantStream,
                        connectivity = connectivity,
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
                        assistantEnablement = assistantEnablement,
                        // The device-local storage-provider choice (#210) — sourced from AppScope, never synced.
                        storageProviderCatalog = storageProviderCatalog,
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
        // online-only anyway. The Destination's own component re-checks availability for its enable/consent gate.
        overlayScope.launch {
            val orgId = resolveAssistantOrgId() ?: return@launch
            when (val availability = assistantClient.availability(orgId)) {
                is RemoteSnapshot.Available ->
                    if (availability.value.entitled) _destinations.value = Destination.entries
                is RemoteSnapshot.Unavailable -> Unit
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
            .stateIn(overlayScope, SharingStarted.WhileSubscribed(5_000L), rootChrome(""))

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
                            flowOf(rootChrome("", onRefresh = child.component::onRefresh))
                        is MainShellComponent.PlanChild.Detail ->
                            child.component.state.map { drilledChrome(it.task?.title ?: "Task") }
                    }
                }

            // Tasks is the multi-pane workspace (ADR-0007 t2): its co-resident panes carry their own
            // headers (title · back · refresh), so the shell bar shows only the global actions here — no
            // title — and Tasks needs no View change. The single-pane Destinations title the bar instead.
            // Drop the capture actions (→ no FAB pair) while the Item tree is in move mode: the FABs sit
            // bottom-centre now and would cover the modal move bar's ↑↓‹›/Done controls otherwise.
            is MainShellComponent.DestinationChild.Tasks ->
                active.component.tree.state.map { rootChrome("", capture = it.moveMode == null) }

            // Settings is a tier-3 stack: the category list ("Settings") or a drilled category (its title).
            is MainShellComponent.DestinationChild.Settings ->
                active.component.stack.asFlow().map { ss ->
                    when (val child = ss.active.instance) {
                        is SettingsComponent.SettingsChild.List -> rootChrome("Settings")
                        is SettingsComponent.SettingsChild.Detail -> drilledChrome(child.category.chromeTitle())
                    }
                }

            // The Calendar New pre-dates to its selected day (#74) — wire that as this root's New handler.
            is MainShellComponent.DestinationChild.Calendar ->
                flowOf(rootChrome("Calendar", onNew = active.component::onNewForSelectedDay))

            // The Assistant chat (ADR-0040): on iOS the SwiftUI View owns its own chrome; the shared bar is
            // only rendered on the deferred Android/desktop Views, so a plain titled root bar suffices.
            is MainShellComponent.DestinationChild.Assistant -> flowOf(rootChrome("Assistant"))
            is MainShellComponent.DestinationChild.Inbox -> flowOf(rootChrome("Inbox"))
            is MainShellComponent.DestinationChild.Profile -> flowOf(rootChrome("Profile"))
            is MainShellComponent.DestinationChild.Activity -> flowOf(rootChrome("Activity"))
            is MainShellComponent.DestinationChild.Placeholder -> flowOf(rootChrome(active.destination.name))
        }

    /** A Destination-root chrome: ☰ menu + title + a per-screen Refresh (if any) and the global create
     *  actions (Brain dump, New). New defaults to the undated overlay; the Calendar root pre-dates it. */
    private fun rootChrome(
        title: String,
        onRefresh: (() -> Unit)? = null,
        onNew: () -> Unit = { openOverlay(OverlayRoute.New()) },
        capture: Boolean = true,
    ): ChromeSpec = ChromeSpec(
        title = title,
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
    private fun drilledChrome(title: String): ChromeSpec = ChromeSpec(title = title, drilled = true)

    /** The shell-chrome title for a drilled Settings category (shell presentation, like the root titles). */
    private fun SettingsCategory.chromeTitle(): String = when (this) {
        SettingsCategory.Appearance -> "Appearance"
        SettingsCategory.TaskBehavior -> "Task behavior"
        SettingsCategory.SpeechEngine -> "Speech engine"
        SettingsCategory.Agent -> "Agent"
        SettingsCategory.Assistant -> "Assistant"
        SettingsCategory.Storage -> "Storage"
        SettingsCategory.DataPrivacy -> "Data & Privacy"
        SettingsCategory.HelpFeedback -> "Help & Feedback"
        SettingsCategory.AppPermissions -> "App Permissions"
        SettingsCategory.Legal -> "Legal"
        SettingsCategory.Account -> "Account"
        SettingsCategory.Security2FA -> "Security & 2FA"
        SettingsCategory.Integrations -> "Integrations"
    }

    private fun createOverlay(
        route: OverlayRoute,
        childContext: ComponentContext,
    ): MainShellComponent.OverlayChild =
        when (route) {
            OverlayRoute.Placeholder -> MainShellComponent.OverlayChild.Placeholder

            OverlayRoute.Search ->
                MainShellComponent.OverlayChild.Search(
                    DefaultSearchComponent(
                        componentContext = childContext,
                        searchTasks = searchTasks,
                        output = ::onSearchOutput,
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
 * Dismiss the Tasks Destination's open detail (ADR-0034). The Item [TasksComponent.tree] is the
 * always-present primary pane, so the only dismissible co-resident pane is the detail: back closes it and
 * reveals the tree beneath; at the bare tree, back is not consumed (the shell's outer back takes over).
 */
private fun TasksComponent.dismissForegroundPane(): Boolean {
    detail.value.child?.instance?.let { it.onCloseClicked(); return true }
    return false
}

/**
 * Map a persisted [BrainDumpDraft] to the online-only create payload (ADR-0015 Inbox amendment) — the
 * Inbox accept path. The persisted draft is **flat** (no inter-draft parent/child/sequence relations —
 * dropped at extraction, ADR-0027 flat-create), so this is the simple field copy: notes → description,
 * and `completeBy` becomes a start-of-day instant in [timeZone] (mirroring the Brain dump overlay's
 * `DraftTask.toCreatePayload`).
 */
private fun BrainDumpDraft.toCreatePayload(timeZone: String): CreateItem.Payload {
    val zone = runCatching { TimeZone.of(timeZone) }.getOrDefault(TimeZone.UTC)
    return CreateItem.Payload.Task(
        CreateTaskPayload(
            title = title.trim(),
            description = notes?.ifBlank { null },
            completeBy = completeBy?.atStartOfDayIn(zone)?.toString(),
            deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
        ),
    )
}

/** No-op Calendar read source — the shell's test default when no Account session supplies one (#74). */
private val NoopCalendarRepository = object : CalendarRepository {
    override fun observeMarkers(from: LocalDate, to: LocalDate) =
        MutableStateFlow<Map<LocalDate, Int>>(emptyMap())

    override fun observeDay(date: LocalDate) = MutableStateFlow<List<CalendarItem>>(emptyList())
    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/** No-op occurrence-act seam — the shell's test default (a real one is command-backed, #74). */
private val NoopOccurrenceEditor = object : OccurrenceEditor {
    override suspend fun mark(itemId: String, action: OccurrenceAction) {}
    override suspend fun clear(itemId: String) {}
    override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
}

/**
 * Inert Assistant client (ADR-0040) — the shell's default when no host wires the real one: every call is
 * Unavailable, so the availability gate stays `entitled = false` and the Assistant Destination stays absent.
 * Used by the Android/desktop hosts (their Assistant Views are deferred) and the shell component tests; only
 * the iOS host passes the real client, so the Destination appears only there in v1.
 */
internal val InertAssistantClient = object : AssistantClient {
    override suspend fun availability(orgId: OrgId) = RemoteSnapshot.Unavailable
    override suspend fun setEnablement(orgId: OrgId, enabled: Boolean) = RemoteSnapshot.Unavailable
    override suspend fun apply(orgId: OrgId, proposal: AssistantProposal) = RemoteSnapshot.Unavailable
    override suspend fun conversations(orgId: OrgId) = RemoteSnapshot.Unavailable
    override suspend fun conversation(orgId: OrgId, id: ConversationId) = RemoteSnapshot.Unavailable
}

/** Inert Conversation cache (ADR-0040) — the shell's default when no Account session supplies one: empty,
 *  read-only. Paired with [InertAssistantClient] so a never-shown Assistant Destination needs no real store. */
private val InertConversationStore = object : ConversationStore {
    override fun observeConversations() = flowOf(emptyList<Conversation>())
    override fun observeMessages(id: ConversationId) = flowOf(emptyList<ChatMessage>())
    override suspend fun upsertConversation(conversation: Conversation) {}
    override suspend fun upsertMessage(conversationId: ConversationId, message: ChatMessage) {}
    override suspend fun upsertMessages(conversationId: ConversationId, messages: List<ChatMessage>) {}
}

/** No-op feedback service — the shell's test default when no AppComponent supplies one (#375). */
internal val NoopFeedbackRepository = object : FeedbackRepository {
    override suspend fun submit(draft: com.circuitstitch.deferno.core.data.feedback.FeedbackDraft): FeedbackResult =
        FeedbackResult.Offline
}

package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.attachment.OnDeviceStorageUsage
import com.circuitstitch.deferno.core.data.backup.ImportResult
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.data.outbox.FlushResult
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.BlockedByResult
import com.circuitstitch.deferno.core.data.comment.CommentRepository
import com.circuitstitch.deferno.core.data.comment.CommentWriter
import com.circuitstitch.deferno.core.data.history.ItemHistoryRepository
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.AddToPlan
import com.circuitstitch.deferno.core.domain.command.ClearOccurrence
import com.circuitstitch.deferno.core.domain.command.ClearTaskDeadline
import com.circuitstitch.deferno.core.domain.command.CommandExecutor
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.domain.command.DeleteTask
import com.circuitstitch.deferno.core.domain.command.MarkOccurrence
import com.circuitstitch.deferno.core.domain.command.MoveItem
import com.circuitstitch.deferno.core.domain.command.RemoveFromPlan
import com.circuitstitch.deferno.core.domain.command.RescheduleOccurrence
import com.circuitstitch.deferno.core.domain.command.SetDefinitionState
import com.circuitstitch.deferno.core.domain.command.SetDoneVisibility
import com.circuitstitch.deferno.core.domain.command.SetDragAndDrop
import com.circuitstitch.deferno.core.domain.command.SetTaskBlockedBy
import com.circuitstitch.deferno.core.domain.command.SetTaskDeadline
import com.circuitstitch.deferno.core.domain.command.SetTaskLabels
import com.circuitstitch.deferno.core.domain.command.SetTaskPinned
import com.circuitstitch.deferno.core.domain.command.SetTheme
import com.circuitstitch.deferno.core.domain.command.SetTracking
import com.circuitstitch.deferno.core.domain.command.taskCommandFor
import com.circuitstitch.deferno.core.data.braindump.brainDumpRecordingPlaceholderId
import com.circuitstitch.deferno.core.di.AccountComponent
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.coroutines.flow.first
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import com.circuitstitch.deferno.feature.settings.SettingsEditor
import com.circuitstitch.deferno.feature.tasks.BlockedByEditor
import com.circuitstitch.deferno.feature.tasks.DefinitionStateEditor
import com.circuitstitch.deferno.feature.tasks.MoveEditor
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachment
import com.circuitstitch.deferno.feature.tasks.OnDeviceAttachments
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import kotlinx.coroutines.flow.Flow
import com.circuitstitch.deferno.core.domain.command.SetTaskDeadlineTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * The per-Account data the [RootComponent]'s Main shell is built over (ADR-0014): the offline-first
 * repositories the Views observe, plus the offline write paths (add-to-plan and working-state edits go
 * through the command executor → optimistic apply + outbox enqueue). It is the seam the shell depends
 * on, so the shell stays testable on fakes while production builds it from the real [AccountComponent].
 *
 * Rebuilt when the Active Account switches (the [RootComponent] re-keys its Main child), so each
 * session holds exactly one Account's repositories — the per-Account isolation boundary (ADR-0002).
 */
interface AccountSession {
    val taskRepository: TaskRepository

    /**
     * The cross-kind Item read the Tasks Item tree renders (ADR-0034, #226/#227) + the device-local
     * [foldStore] of expand/collapse overrides the tree and the detail subtask outline share.
     */
    val itemRepository: ItemRepository
    val foldStore: ItemFoldStore

    /** The Task detail's online-only attachments source (comments + history are now offline-first, ADR-0043). */
    val taskDetailRepository: TaskDetailRepository

    /**
     * The offline-first Task-detail ACTIVITY feed (ADR-0043): the comment thread + cached server item
     * history observed from the cache, the [commentWriter] optimistic post/edit/delete seam, and the
     * device-local [currentUserId] (the Active Account's user id) that gates own-comment affordances with
     * NO live /auth/me. Defaulted to the empty/no-op instances so test fakes build without them.
     */
    val commentRepository: CommentRepository get() = CommentRepository.NONE
    val itemHistoryRepository: ItemHistoryRepository get() = ItemHistoryRepository.NONE
    val commentWriter: CommentWriter get() = CommentWriter.NONE
    val currentUserId: UserId? get() = null

    /**
     * The Task detail's **on-device** attachment seam (#210/#211) — this Account's locally-stored
     * attachments (e.g. a retained brain-dump recording), read/deleted/played locally, distinct from the
     * synced [taskDetailRepository] attachments. Backed by the Account's `LocalAttachmentRepository`.
     */
    val onDeviceAttachments: OnDeviceAttachments

    /**
     * On-device storage usage for the Settings > Storage read-out (#211) — this Account's kept brain-dump
     * recordings (largest first), observed offline-first. Backed by the Account's `LocalAttachmentRepository`.
     */
    val onDeviceStorageUsage: OnDeviceStorageUsage

    /**
     * Build this Account's on-device Backup zip (#313, ADR-0041): a one-shot read of the four local stores
     * serialized to an `items.json`-only zip. Defaulted to an empty zip so test fakes build without it.
     */
    suspend fun buildBackupZip(): ByteArray = ByteArray(0)

    /**
     * Restore this Account's items from an on-device Backup zip (#314, ADR-0041): parse + version-gate +
     * replay each item as an id-preserving create on the outbox. Defaulted to [ImportResult.Malformed] so
     * test fakes build without it; production backs it with the Account's `backupImporter`.
     */
    suspend fun importBackup(bytes: ByteArray): ImportResult = ImportResult.Malformed
    val planRepository: PlanRepository

    /**
     * The Active Account's settings — observed for the Settings Destination (#72) and the app-wide
     * **live theme** (the theme StateFlow the root derives from this drives `DefernoTheme`).
     */
    val settingsRepository: SettingsRepository

    /**
     * The Security & 2FA seam (Settings → Security, #72 follow-through): 2FA status/enrollment +
     * connected devices over the first-party backend contract, with the step-up cookie held per
     * Account session. Defaulted to the inert repository (every call Unavailable) so test fakes
     * build without it; production backs it with the Account component's [SecurityRepository].
     */
    val securityRepository: SecurityRepository get() = SecurityRepository.Inert

    /**
     * The User-setting write seam the Settings Destination drives (#72, #173): each backed-category
     * intent maps to its per-field `SettingsCommand` and dispatches through the command executor
     * (optimistic local apply + outbox enqueue for `PATCH /auth/me/settings`), so the feature layer
     * never touches the registry — or `SettingsWriter` — directly (mirrors [workingStateEditor] /
     * [occurrenceEditor]).
     */
    val settingsEditor: SettingsEditor

    /** Add [taskId] to the ([date], [tz]) plan — optimistic apply + outbox enqueue (ADR-0001/0007). */
    suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String)

    /** Remove [taskId] from the ([date], [tz]) plan — the Item tree menu's plan toggle off-direction (#231). */
    suspend fun removeFromPlan(taskId: TaskId, date: LocalDate, tz: String)

    /**
     * The Item tree command menu's **Pin** write seam (#231): maps a [TaskId] + target flag to the
     * `SetTaskPinned` Command and dispatches it through the command executor (optimistic apply + outbox
     * enqueue), so the feature layer never touches the registry directly (mirrors [deleteTask]).
     */
    val setPinned: suspend (TaskId, Boolean) -> Unit

    /**
     * The working-state write seam the Tasks detail drives (#73). Maps a target [WorkingState] to its
     * one lifecycle Command and dispatches it through the command executor (optimistic apply + outbox
     * enqueue), gated by the supplied cached row — so the shell drives the offline write without the
     * feature layer touching the command registry directly (mirrors the [addToPlan] wrapper).
     */
    val workingStateEditor: WorkingStateEditor

    /**
     * The Item tree command menu's **non-Task status** write seam (#299): the recurring-definition "light
     * switch" (Archive/Restore). Maps an `(id, kind, target)` to the `SetDefinitionState` Command and
     * dispatches it through the command executor (optimistic per-kind apply + outbox enqueue), so the
     * feature layer never touches the registry directly — the Habit/Chore/Event mirror of
     * [workingStateEditor]. Defaulted to the no-op NONE so test fakes build without overriding it.
     */
    val definitionStateEditor: DefinitionStateEditor get() = DefinitionStateEditor.NONE

    /**
     * The Task detail's editable-PROPERTIES write seams (DUE date + LABELS), each routed through the
     * command executor (optimistic apply + outbox enqueue, ADR-0001/0007) so the feature layer never
     * touches the command registry directly (mirrors [workingStateEditor]). [setDeadline] maps a non-null
     * `completeBy` to `SetTaskDeadline` and a `null` to the explicit `ClearTaskDeadline`; [setLabels]
     * replaces the Task's label set via `SetTaskLabels`.
     */
    val setDeadline: suspend (TaskId, Instant?) -> Unit
    val setLabels: suspend (TaskId, List<String>) -> Unit

    /**
     * The Task detail's deadline **clock-time** write seam (#348): maps a `(TaskId, LocalTime?)` to the
     * `SetTaskDeadlineTime` Command and dispatches it through the command executor (optimistic apply +
     * outbox enqueue). A `null` time = all-day. Paired with [setDeadline]'s date axis so the combined
     * date+time WHEN picker (iOS) can edit the time. Defaulted to a no-op so test fakes build without it.
     */
    val setDeadlineTime: suspend (TaskId, LocalTime?) -> Unit get() = { _, _ -> }

    /**
     * The Task detail's **Delete** write seam (the kebab → confirm): maps a [TaskId] to the destructive
     * `DeleteTask` Command and dispatches it through the command executor (optimistic local apply + outbox
     * enqueue, ADR-0001/0007), so the feature layer never touches the registry directly (mirrors [setDeadline]).
     */
    val deleteTask: suspend (TaskId) -> Unit

    /**
     * The Tasks Item-tree move seam the modal move mode drives (ADR-0034 #228): maps a relative move to a
     * `MoveItem` Command and dispatches it through the command executor (optimistic cross-kind reorder +
     * outbox enqueue), so the feature layer never touches the registry directly (mirrors [workingStateEditor]).
     */
    val moveEditor: MoveEditor

    /**
     * The Item-tree "Blocked by…" dependency-edge seam (#291): maps the picker's target blocker set to
     * the ONLINE-ONLY `SetTaskBlockedBy` Command and dispatches it through the command executor,
     * returning the data-layer verdict (Applied / Offline / Failed) the tree surfaces — unlike the
     * fire-and-forget offline-first editors. Defaulted to the always-Applied NONE so test fakes build.
     */
    val blockedByEditor: BlockedByEditor get() = BlockedByEditor.NONE

    /** The Calendar Destination's windowed feed read source (#74) — the month grid + day agenda observe it. */
    val calendarRepository: CalendarRepository

    /**
     * The Assistant Destination's on-device Conversation cache (ADR-0040, #282): the source of truth for
     * readable chat history — the component persists each turn as it streams and reads it back offline.
     * Local-only, never synced (turns are never outbox-queued — online-only to extend, ADR-0040).
     */
    val conversationStore: ConversationStore

    /**
     * The Inbox Destination's local Brain dump drafts (ADR-0015 Inbox amendment): the on-device worker
     * writes Ready drafts, and the Inbox observes them (list + nav badge). A function seam (not the
     * concrete repository) so the shell stays testable on fakes. Local-only, never synced.
     */
    fun observeBrainDumpDrafts(): Flow<List<BrainDumpDraft>>

    /** Persist a draft's status change (the Inbox accept's mark-Accepted, and dismiss / undo). */
    suspend fun upsertBrainDumpDraft(draft: BrainDumpDraft)

    /**
     * The Activity Destination's reverse-chronological feed (#260): the offline-first ledger of every
     * applied write, observed live so it re-emits as new changes land. A function seam (not the concrete
     * store) so the shell stays testable on fakes.
     */
    fun observeActivity(): Flow<List<ActivityEntry>>

    /**
     * Attach this Account's retained brain-dump recording for [draft] to the just-created Task [taskId]
     * (#211, ADR-0015 Inbox accept). A no-op when no recording was retained (the setting was off at
     * capture, or this isn't a brain-dump platform). The Inbox accept calls it with the created Task id
     * surfaced by the online create (`CommandResult.Accepted.itemId`).
     */
    suspend fun attachBrainDumpRecording(taskId: String, draft: BrainDumpDraft)

    /**
     * The occurrence-act seam the Calendar drives (#74): mark / clear / reschedule a firing through the
     * command executor (optimistic apply + outbox enqueue), so the feature layer never touches the
     * registry directly — the firing-level mirror of [workingStateEditor].
     */
    val occurrenceEditor: OccurrenceEditor

    /**
     * Create a new item (offline-first, #185): routes [payload] through the command executor's
     * [CreateItem] command, which optimistically inserts the local row under a client-generated id and
     * enqueues a replayable create on the outbox. Returns the [CommandResult] — always Accepted (queued,
     * carrying the new id) regardless of connectivity. The New surface dismisses on Accepted; the row is
     * already observable via the repository `Flow`.
     */
    suspend fun create(payload: CreateItem.Payload): CommandResult

    /**
     * Drain this Account's offline outbox (#23, #143): replay the queued writes FIFO-with-backoff as
     * of [now] and LWW-reconcile after a successful pass. The [RootComponent] drives it — on session
     * activation (before the settings reconcile, so the pull can't clobber a queued change) and then
     * periodically while the session is active.
     */
    suspend fun flushOutbox(now: Instant): FlushResult
}

/**
 * Production [AccountSession] over the per-Account [AccountComponent] DI graph (ADR-0014). The
 * component owns the Account's encrypted DB, repositories, outbox, and command executor; this adapts
 * the slice the shell needs.
 */
class AccountComponentSession(private val component: AccountComponent) : AccountSession {
    override val taskRepository: TaskRepository get() = component.taskRepository
    override val itemRepository: ItemRepository get() = component.itemRepository
    override val foldStore: ItemFoldStore get() = component.foldStore
    override val taskDetailRepository: TaskDetailRepository get() = component.taskDetailRepository
    override val commentRepository: CommentRepository get() = component.commentRepository
    override val itemHistoryRepository: ItemHistoryRepository get() = component.itemHistoryRepository
    override val commentWriter: CommentWriter get() = component.commentWriter
    // Device-local identity (ADR-0043): Account.id == User.id for a signed-in account, so own-comment
    // affordances + "You" attribution work with NO live /auth/me (offline-first invariant).
    override val currentUserId: UserId? get() = UserId(component.activeAccount.id.value)
    override val planRepository: PlanRepository get() = component.planRepository

    // #211: map this Account's LocalAttachment rows → the detail's OnDeviceAttachment projection.
    override val onDeviceAttachments: OnDeviceAttachments = object : OnDeviceAttachments {
        override suspend fun forTask(taskId: TaskId): List<OnDeviceAttachment> =
            component.localAttachmentRepository.forTask(taskId.value).map {
                OnDeviceAttachment(it.id, it.filename, it.mime, it.size, it.caption)
            }

        override suspend fun delete(id: String) = component.localAttachmentRepository.delete(id)

        override suspend fun bytes(id: String): ByteArray? = component.localAttachmentRepository.bytes(id)
    }

    // #211: the Settings > Storage usage read-out over this Account's on-device recordings (largest first).
    override val onDeviceStorageUsage: OnDeviceStorageUsage =
        OnDeviceStorageUsage { component.localAttachmentRepository.observeBrainDumpRecordings() }

    // #313: the on-device Backup-zip builder over this Account's four local stores (ADR-0041).
    override suspend fun buildBackupZip(): ByteArray = component.backupExporter.buildBackupZip()
    // #314: the on-device import/restore over this Account's outbox (ADR-0041).
    override suspend fun importBackup(bytes: ByteArray): ImportResult = component.backupImporter.import(bytes)
    override val settingsRepository: SettingsRepository get() = component.settingsRepository
    override val securityRepository: SecurityRepository get() = component.securityRepository
    override val calendarRepository: CalendarRepository get() = component.calendarRepository
    override val conversationStore: ConversationStore get() = component.conversationStore

    override fun observeBrainDumpDrafts() = component.brainDumpDraftRepository.observeDrafts()

    override fun observeActivity() = component.activityLedgerStore.recent()

    override suspend fun upsertBrainDumpDraft(draft: BrainDumpDraft) {
        component.brainDumpDraftRepository.upsert(draft)
        // #211: a draft leaving the Ready queue (accept marks it Accepted, dismiss marks it Dismissed) is a
        // triage. Once a recording's LAST Ready draft is triaged, reap its retained WAV so dismiss-all leaves
        // no orphan and on-device storage stays bounded. Undo (→ Ready) and the worker's Ready inserts skip it.
        if (draft.status != BrainDumpDraftStatus.Ready) reapBrainDumpRecordingIfTriaged(draft)
    }

    // Delete the retained recording for [draft]'s recording when no Ready draft from it remains. The mark
    // above is already committed, so the same-driver re-query sees the just-triaged draft as non-Ready.
    private suspend fun reapBrainDumpRecordingIfTriaged(draft: BrainDumpDraft) {
        val stillReady = component.brainDumpDraftRepository.observeDrafts().first()
            .any { it.status == BrainDumpDraftStatus.Ready && it.createdAt == draft.createdAt }
        if (!stillReady) {
            component.localAttachmentRepository.delete(brainDumpRecordingPlaceholderId(draft.createdAt))
        }
    }

    override suspend fun attachBrainDumpRecording(taskId: String, draft: BrainDumpDraft) {
        val attachments = component.localAttachmentRepository
        val bytes = attachments.bytes(brainDumpRecordingPlaceholderId(draft.createdAt)) ?: return
        // Attach to EACH accepted Task (the chosen one→N model): a per-Task copy keyed by the Task id, so
        // deleting one Task's attachment never affects a sibling's. The placeholder is reaped on full triage.
        attachments.save(
            id = "braindump:$taskId",
            taskId = taskId,
            filename = "brain-dump-${draft.createdAt.toEpochMilliseconds()}.wav",
            mime = "audio/wav",
            bytes = bytes,
            createdAt = draft.createdAt,
        )
    }

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        component.commandExecutor.execute(AddToPlan(taskId, date, tz))
    }

    override suspend fun removeFromPlan(taskId: TaskId, date: LocalDate, tz: String) {
        component.commandExecutor.execute(RemoveFromPlan(taskId, date, tz))
    }

    override val setPinned: suspend (TaskId, Boolean) -> Unit =
        commandSetPinned(component.commandExecutor)

    override val workingStateEditor: WorkingStateEditor =
        commandWorkingStateEditor(component.commandExecutor)

    override val definitionStateEditor: DefinitionStateEditor =
        commandDefinitionStateEditor(component.commandExecutor)

    override val setDeadline: suspend (TaskId, Instant?) -> Unit =
        commandSetDeadline(component.commandExecutor)

    override val setDeadlineTime: suspend (TaskId, LocalTime?) -> Unit =
        commandSetDeadlineTime(component.commandExecutor)

    override val setLabels: suspend (TaskId, List<String>) -> Unit =
        commandSetLabels(component.commandExecutor)

    override val deleteTask: suspend (TaskId) -> Unit =
        commandDeleteTask(component.commandExecutor)

    override val moveEditor: MoveEditor =
        commandMoveEditor(component.commandExecutor)

    override val blockedByEditor: BlockedByEditor =
        commandBlockedByEditor(component.commandExecutor)

    override val occurrenceEditor: OccurrenceEditor =
        commandOccurrenceEditor(component.commandExecutor)

    override val settingsEditor: SettingsEditor =
        commandSettingsEditor(component.commandExecutor)

    override suspend fun create(payload: CreateItem.Payload): CommandResult =
        component.commandExecutor.execute(CreateItem(payload))

    override suspend fun flushOutbox(now: Instant): FlushResult =
        component.outboxProcessor.flush(now)
}

/**
 * A [WorkingStateEditor] backed by a [CommandExecutor]: converts a target [WorkingState] to its one
 * lifecycle Command ([taskCommandFor]) and dispatches it with the cached row for the pre-flight gate
 * (ADR-0007). Shared by production and tests so the mapping isn't duplicated.
 */
internal fun commandWorkingStateEditor(executor: CommandExecutor): WorkingStateEditor =
    WorkingStateEditor { id: TaskId, target: WorkingState, current: Task? ->
        executor.execute(taskCommandFor(id, target), current = current)
    }

/**
 * A [DefinitionStateEditor] backed by a [CommandExecutor] (#299): dispatches the single
 * [SetDefinitionState] command carrying the resolved [ItemKind] so the writer routes the per-kind PATCH.
 * No `current` row — a definition state set has no stale-transition gate (the View hides the no-op verb).
 * Shared by production and tests so the mapping isn't duplicated — the recurring-kind mirror of
 * [commandWorkingStateEditor].
 */
internal fun commandDefinitionStateEditor(executor: CommandExecutor): DefinitionStateEditor =
    DefinitionStateEditor { id: String, kind: ItemKind, target: DefinitionState ->
        executor.execute(SetDefinitionState(id, kind, target))
    }

/**
 * The deadline DUE-date write seam backed by a [CommandExecutor]: a non-null `completeBy` dispatches
 * [SetTaskDeadline], a `null` the explicit [ClearTaskDeadline] (the writer emits an explicit clear, not
 * an empty set). No `current` row is passed — neither command has a stale-transition gate, matching the
 * other property edits. Shared by production and tests so the mapping isn't duplicated.
 */
internal fun commandSetDeadline(executor: CommandExecutor): suspend (TaskId, Instant?) -> Unit =
    { id, completeBy ->
        if (completeBy == null) {
            executor.execute(ClearTaskDeadline(id))
        } else {
            executor.execute(SetTaskDeadline(id, completeBy))
        }
    }

/**
 * The deadline **clock-time** write seam backed by a [CommandExecutor] (#348): dispatches the single
 * [SetTaskDeadlineTime] command carrying the picked [LocalTime] (a `null` = all-day). No `current` row
 * is passed — like [commandSetDeadline], a time set has no stale-transition gate. Shared by production
 * and tests so the mapping isn't duplicated.
 */
internal fun commandSetDeadlineTime(executor: CommandExecutor): suspend (TaskId, LocalTime?) -> Unit =
    { id, timeOfDay ->
        executor.execute(SetTaskDeadlineTime(id, timeOfDay))
    }

/**
 * The LABELS write seam backed by a [CommandExecutor]: replaces the Task's label set via [SetTaskLabels]
 * (an empty list clears them — the field is always present). Shared by production and tests.
 */
internal fun commandSetLabels(executor: CommandExecutor): suspend (TaskId, List<String>) -> Unit =
    { id, labels -> executor.execute(SetTaskLabels(id, labels)) }

/**
 * The Delete write seam backed by a [CommandExecutor]: dispatches the destructive [DeleteTask] (the
 * writer marks the row deleted + enqueues `DELETE /tasks/{id}`). No `current` row — the command's own
 * `enabledFor` gate handles the already-deleted case. Shared by production and tests.
 */
internal fun commandDeleteTask(executor: CommandExecutor): suspend (TaskId) -> Unit =
    { id -> executor.execute(DeleteTask(id)) }

/**
 * The Pin write seam backed by a [CommandExecutor] (#231): dispatches [SetTaskPinned] with the target flag
 * (a single boolean toggle — the menu picks Pin/Unpin from the current value). No `current` row — pinning
 * has no stale-transition gate. Mirrors the other `command*` seam factories above.
 */
internal fun commandSetPinned(executor: CommandExecutor): suspend (TaskId, Boolean) -> Unit =
    { id, pinned -> executor.execute(SetTaskPinned(id, pinned)) }

/**
 * The Tasks Item-tree move seam backed by a [CommandExecutor] (ADR-0034 #228): dispatches a [MoveItem]
 * with the destination parent + insertion index the modal move mode computed (optimistic cross-kind
 * reorder + outbox enqueue). No `current` row — a move has no stale-transition gate. Shared by production
 * and tests so the mapping isn't duplicated.
 */
internal fun commandMoveEditor(executor: CommandExecutor): MoveEditor =
    MoveEditor { id, newParentId, position -> executor.execute(MoveItem(id, newParentId, position)) }

/**
 * The "Blocked by…" dependency-edge seam backed by a [CommandExecutor] (#291): dispatches the
 * ONLINE-ONLY [SetTaskBlockedBy] and maps the [CommandResult] back to the data-layer verdict the tree
 * component surfaces (Accepted → Applied, Offline → Offline, Failed → Failed with the server message).
 * A [CommandResult.Rejected] can't occur (the kind has no enablement rule) but maps to Failed so the
 * mapping stays total. Shared by production and tests so the mapping isn't duplicated.
 */
internal fun commandBlockedByEditor(executor: CommandExecutor): BlockedByEditor =
    BlockedByEditor { id, blockers ->
        when (val result = executor.execute(SetTaskBlockedBy(id, blockers))) {
            is CommandResult.Accepted -> BlockedByResult.Applied
            is CommandResult.Offline -> BlockedByResult.Offline
            is CommandResult.Failed -> BlockedByResult.Failed(result.message)
            is CommandResult.Rejected -> BlockedByResult.Failed(result.reason.name)
        }
    }

/**
 * An [OccurrenceEditor] backed by a [CommandExecutor] (#74): each act maps to its occurrence Command
 * and dispatches it (optimistic apply + outbox enqueue). Shared by production and tests so the mapping
 * isn't duplicated — the firing-level mirror of [commandWorkingStateEditor].
 */
internal fun commandOccurrenceEditor(executor: CommandExecutor): OccurrenceEditor =
    object : OccurrenceEditor {
        override suspend fun mark(itemId: String, action: OccurrenceAction) {
            executor.execute(MarkOccurrence(itemId, action))
        }

        override suspend fun clear(itemId: String) {
            executor.execute(ClearOccurrence(itemId))
        }

        override suspend fun reschedule(itemId: String, newDate: LocalDate) {
            executor.execute(RescheduleOccurrence(itemId, newDate))
        }
    }

/**
 * A [SettingsEditor] backed by a [CommandExecutor] (#173): each backed-category intent maps to its
 * per-field `SettingsCommand` ([SetTheme] / [SetTracking] / [SetDragAndDrop] / [SetDoneVisibility])
 * and dispatches it through the registry (ADR-0007) — the optimistic apply + outbox enqueue stays
 * inside `OutboxSettingsWriter` (ADR-0001), only the call path changed. The User-setting mirror of
 * [commandOccurrenceEditor]; shared by production and tests so the mapping isn't duplicated.
 */
internal fun commandSettingsEditor(executor: CommandExecutor): SettingsEditor =
    object : SettingsEditor {
        override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) {
            executor.execute(SetTheme(family, mode))
        }

        override suspend fun setTracking(enabled: Boolean) {
            executor.execute(SetTracking(enabled))
        }

        override suspend fun setDragAndDrop(enabled: Boolean) {
            executor.execute(SetDragAndDrop(enabled))
        }

        override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) {
            executor.execute(SetDoneVisibility(globalSeconds, dashboardSeconds))
        }
    }

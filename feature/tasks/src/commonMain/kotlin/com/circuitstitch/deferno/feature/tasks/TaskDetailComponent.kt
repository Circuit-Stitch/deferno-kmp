package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** One node of the Task's recursive subtask tree: the child [task] and its own [children] subtree. */
data class SubtaskNode(val task: Task, val children: List<SubtaskNode>)

/**
 * Observable state for the Task detail pane. [task] is null until the local row is observed.
 *
 * Beyond the core Task, it carries the three web-parity detail sections (#27 follow-up):
 * - the recursive [subtasks] tree (built locally from the cached Task list) + its done/total progress;
 * - the online-only [comments] thread (the web "Activity" feed) with its load/post flags;
 * - the online-only read-only [attachments] list.
 * [currentUserId] gates which comments offer edit/delete affordances (the server enforces the rest).
 */
data class TaskDetailState(
    val task: Task? = null,
    val isHydrating: Boolean = false,
    val subtasks: List<SubtaskNode> = emptyList(),
    val subtaskDone: Int = 0,
    val subtaskTotal: Int = 0,
    val comments: List<Comment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsError: Boolean = false,
    val isPostingComment: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val isUploadingAttachment: Boolean = false,
    // On-device attachments held locally (e.g. a retained brain-dump recording, #210/#211) — distinct
    // from the synced [attachments] above: they live on this device, so they're played/deleted locally,
    // not opened from a signed URL. Empty on platforms without on-device capture (desktop/iOS).
    val onDeviceAttachments: List<OnDeviceAttachment> = emptyList(),
    val currentUserId: UserId? = null,
)

/**
 * A locally-stored attachment on a Task (#210/#211) as the detail View renders it — a thin, transport-free
 * projection of `core/data`'s `LocalAttachment` (the shell maps it across so this module needn't depend on
 * the storage layer's type). Unlike the synced [Attachment] there is no URL: the bytes are on-device, so an
 * audio recording is **played locally** rather than opened in a browser.
 */
data class OnDeviceAttachment(
    val id: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val caption: String? = null,
) {
    /** Whether this is audio (a brain-dump recording) — drives the View's play affordance. */
    val isAudio: Boolean get() = mime.startsWith("audio/")
}

/**
 * The Task detail's **on-device attachment** seam (#210/#211): lists a Task's locally-stored attachments
 * (e.g. a retained brain-dump recording), deletes one, and reads one's bytes for local playback. The shell
 * backs it with this Account's `LocalAttachmentRepository` (mapping its `LocalAttachment` → [OnDeviceAttachment]
 * so this module stays free of the storage layer's type). [NONE] is the empty default for tests and the
 * platforms without on-device capture (desktop/iOS) — then the detail simply shows no on-device rows.
 */
interface OnDeviceAttachments {
    suspend fun forTask(taskId: TaskId): List<OnDeviceAttachment>
    suspend fun delete(id: String)
    suspend fun bytes(id: String): ByteArray?

    companion object {
        val NONE: OnDeviceAttachments = object : OnDeviceAttachments {
            override suspend fun forTask(taskId: TaskId): List<OnDeviceAttachment> = emptyList()
            override suspend fun delete(id: String) {}
            override suspend fun bytes(id: String): ByteArray? = null
        }
    }
}

/**
 * The Task detail component (ADR-0007: the co-resident detail pane). Observes one Task from the
 * repository and, on creation, requests a [TaskRepository.hydrate] to upgrade the summary row to the
 * full detail (description, ownerOrgId, nextTaskId — #22). It also builds the recursive subtask tree
 * from the cached Task list and loads the online-only comments + attachments (the web-parity detail
 * sections). Navigation actions are emitted as [Output] intents; the parent owns the co-resident slots.
 */
interface TaskDetailComponent {
    val taskId: TaskId
    val state: StateFlow<TaskDetailState>

    fun onCloseClicked()
    fun onShowTreeClicked()
    fun onAddToPlanClicked()

    /**
     * Move this Task to [target] (#73) — one of the five [WorkingState]s, issued as a Command through
     * the injected [WorkingStateEditor]: optimistic local apply + outbox enqueue (ADR-0001), gated on
     * the current row so a no-op transition writes nothing (ADR-0007). The local DB Flow then re-emits
     * the new state into [state], so the badge flips optimistically.
     */
    fun onSetWorkingState(target: WorkingState)

    /**
     * Set or clear this Task's deadline DUE date — issued as a Command through the injected [setDeadline]
     * seam (optimistic local apply + outbox enqueue, ADR-0001). [date] is the user-picked day at the
     * device zone; it is combined with the Task's current `deadlineTimeOfDay` (or start-of-day when none,
     * matching the create form) into the `completeBy` instant the write seam wants. A `null` [date]
     * **clears** the deadline (the explicit `ClearTaskDeadline` path). The local DB Flow then re-emits the
     * new `completeBy` into [state].
     */
    fun onSetDeadline(date: LocalDate?)

    /**
     * Replace this Task's LABELS with [labels] (an empty list clears them) — issued as a Command through
     * the injected [setLabels] seam (optimistic local apply + outbox enqueue, ADR-0001). The local DB Flow
     * then re-emits the new labels into [state].
     */
    fun onSetLabels(labels: List<String>)

    /** Toggle a subtask between Done and Open (the tree's checkbox) — reuses the working-state write seam. */
    fun onToggleSubtaskDone(subtask: Task)

    /** Open a subtask's own detail (tapping the row, web's chevron) — re-keys the detail to that child. */
    fun onSubtaskClicked(id: TaskId)

    /** Create a new direct child of this Task (the tree's "add subtask" field) — online-only create. */
    fun onAddSubtask(title: String)

    /** Post a new comment to this Task's Activity thread, then re-fetch it. No-op on a blank body. */
    fun onPostComment(body: String)

    /** Edit one of this user's comments, then re-fetch the thread. No-op on a blank body. */
    fun onEditComment(commentId: String, body: String)

    /** Delete one of this user's comments, then re-fetch the thread. */
    fun onDeleteComment(commentId: String)

    /** Upload [files] to this Task (presign → PUT → commit), then re-fetch the list. No-op on empty. */
    fun onAddAttachments(files: List<AttachmentUpload>)

    /** Delete an attachment by [attachmentId], then re-fetch the list. */
    fun onDeleteAttachment(attachmentId: String)

    /** Set or change an attachment's caption, then re-fetch the list. No-op on a blank caption. */
    fun onSetAttachmentCaption(attachmentId: String, caption: String)

    /** Delete an on-device attachment by [attachmentId] (#211), then re-fetch the on-device list. */
    fun onDeleteOnDeviceAttachment(attachmentId: String)

    /**
     * The on-device bytes for [attachmentId] (#211), read entirely on-device — the View uses them to play
     * an audio recording locally. `null` if absent. Suspends; the View calls it from its own scope.
     */
    suspend fun onDeviceAttachmentBytes(attachmentId: String): ByteArray?

    sealed interface Output {
        data object Closed : Output
        data class TreeRequested(val id: TaskId) : Output
        data class SubtaskSelected(val id: TaskId) : Output
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultTaskDetailComponent(
    componentContext: ComponentContext,
    override val taskId: TaskId,
    private val taskRepository: TaskRepository,
    private val output: (TaskDetailComponent.Output) -> Unit,
    // The working-state write seam (#73). Defaults to a no-op so the many existing tests that exercise
    // only the read/navigation paths construct the component without supplying it (ADR-0001/0007).
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The in-memory summary the opener (list row / tree child) already had on screen. It seeds the
    // first state so the title + body render the instant the pane appears, instead of flashing a "Task"
    // placeholder until `observeTask` first emits a cycle later (the title "pop-in"). Null when the
    // opener has no row to hand over (e.g. a Plan-overlay tap) — then it falls back to the empty start.
    initialTask: Task? = null,
    // The online-only comments + attachments source. Defaults to an empty no-op so tests that don't
    // care about the detail sections (and offline construction) build without supplying it.
    private val detailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    // The online-only create seam for "add subtask" (parent, title) — wired from the shell's create
    // command. Defaults to a no-op for the same reason as the editors above.
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
    // The deadline DUE-date write seam (taskId, completeBy) — a non-null Instant sets the deadline, a
    // `null` clears it. Wired from the shell's command executor (Set/ClearTaskDeadline); defaults to a
    // no-op like the editors above so the read/navigation-only tests build without it.
    private val setDeadline: suspend (TaskId, Instant?) -> Unit = { _, _ -> },
    // The LABELS write seam (taskId, labels) — replaces the Task's label set (empty clears). Wired from
    // the shell's command executor (SetTaskLabels); defaults to a no-op for the same reason.
    private val setLabels: suspend (TaskId, List<String>) -> Unit = { _, _ -> },
    // On-device attachments (#210/#211): the read/delete/read-bytes seam wired from this Account's
    // LocalAttachmentRepository. Defaulted to the empty NONE so the many tests and the platforms without
    // on-device capture build without it (the detail then shows no on-device rows).
    private val onDeviceAttachments: OnDeviceAttachments = OnDeviceAttachments.NONE,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : TaskDetailComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val hydrating = MutableStateFlow(true)
    private val extras = MutableStateFlow(Extras())

    override val state: StateFlow<TaskDetailState> =
        combine(
            taskRepository.observeTask(taskId),
            taskRepository.observeTasks(),
            extras,
            hydrating,
        ) { task, all, ex, isHydrating ->
            val tree = subtreeOf(taskId, all)
            val flat = tree.flatten()
            TaskDetailState(
                task = task,
                isHydrating = isHydrating,
                subtasks = tree,
                subtaskDone = flat.count { it.workingState == WorkingState.Done },
                subtaskTotal = flat.size,
                comments = ex.comments,
                commentsLoading = ex.commentsLoading,
                commentsError = ex.commentsError,
                isPostingComment = ex.isPostingComment,
                attachments = ex.attachments,
                isUploadingAttachment = ex.isUploadingAttachment,
                onDeviceAttachments = ex.onDeviceAttachments,
                currentUserId = ex.currentUserId,
            )
            // initialTask seeds the title/body on the very first frame so the pane doesn't flash a "Task"
            // placeholder before observeTask first emits (the title "pop-in").
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskDetailState(task = initialTask, isHydrating = true))

    init {
        scope.launch {
            try {
                taskRepository.hydrate(taskId)
            } finally {
                hydrating.value = false
            }
        }
        scope.launch { extras.update { it.copy(currentUserId = detailRepository.currentUserId()) } }
        loadAttachments()
        refreshOnDeviceAttachments()
        loadComments()
    }

    private fun loadAttachments() {
        scope.launch {
            val attachments = detailRepository.attachments(taskId)
            // null = couldn't load; keep whatever we already have rather than blanking the list.
            extras.update { it.copy(attachments = attachments ?: it.attachments) }
        }
    }

    private fun refreshOnDeviceAttachments() {
        scope.launch {
            val local = onDeviceAttachments.forTask(taskId)
            extras.update { it.copy(onDeviceAttachments = local) }
        }
    }

    private fun loadComments() {
        scope.launch {
            extras.update { it.copy(commentsLoading = true) }
            val comments = detailRepository.comments(taskId)
            extras.update {
                it.copy(
                    comments = comments ?: it.comments,
                    commentsLoading = false,
                    commentsError = comments == null,
                )
            }
        }
    }

    override fun onCloseClicked() {
        output(TaskDetailComponent.Output.Closed)
    }

    override fun onShowTreeClicked() {
        output(TaskDetailComponent.Output.TreeRequested(taskId))
    }

    override fun onAddToPlanClicked() {
        output(TaskDetailComponent.Output.AddToPlanRequested(taskId))
    }

    override fun onSetWorkingState(target: WorkingState) {
        // Pass the currently-observed row as `current` so the executor's pre-flight gate can reject a
        // stale transition (the state the Task is already in) before any write — ADR-0007.
        val current = state.value.task
        scope.launch { workingStateEditor.setWorkingState(taskId, target, current) }
    }

    override fun onSetDeadline(date: LocalDate?) {
        // Reuse the create form's date→completeBy conversion (NewState.toPayload): a picked day becomes a
        // start-of-day instant in the device zone, except here we keep the Task's existing time-of-day so
        // the deadline clock survives a date change (the create form has no existing clock, so it lands on
        // start-of-day — byte-identical to date.atStartOfDayIn(zone)). `null` clears the deadline.
        val completeBy: Instant? = date?.let {
            val timeOfDay = state.value.task?.deadlineTimeOfDay ?: DEFAULT_DEADLINE_TIME
            it.atTime(timeOfDay).toInstant(TimeZone.currentSystemDefault())
        }
        scope.launch { setDeadline(taskId, completeBy) }
    }

    override fun onSetLabels(labels: List<String>) {
        scope.launch { setLabels(taskId, labels) }
    }

    override fun onToggleSubtaskDone(subtask: Task) {
        val target = if (subtask.workingState == WorkingState.Done) WorkingState.Open else WorkingState.Done
        scope.launch { workingStateEditor.setWorkingState(subtask.id, target, subtask) }
    }

    override fun onSubtaskClicked(id: TaskId) {
        output(TaskDetailComponent.Output.SubtaskSelected(id))
    }

    override fun onAddSubtask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        scope.launch { createSubtask(taskId, trimmed) }
    }

    override fun onPostComment(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            extras.update { it.copy(isPostingComment = true) }
            val ok = detailRepository.postComment(taskId, trimmed)
            extras.update { it.copy(isPostingComment = false) }
            if (ok) loadComments()
        }
    }

    override fun onEditComment(commentId: String, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        scope.launch { if (detailRepository.editComment(commentId, trimmed)) loadComments() }
    }

    override fun onDeleteComment(commentId: String) {
        scope.launch { if (detailRepository.deleteComment(commentId)) loadComments() }
    }

    override fun onAddAttachments(files: List<AttachmentUpload>) {
        if (files.isEmpty()) return
        scope.launch {
            extras.update { it.copy(isUploadingAttachment = true) }
            val ok = detailRepository.uploadAttachments(taskId, files)
            extras.update { it.copy(isUploadingAttachment = false) }
            if (ok) loadAttachments()
        }
    }

    override fun onDeleteAttachment(attachmentId: String) {
        scope.launch { if (detailRepository.deleteAttachment(taskId, attachmentId)) loadAttachments() }
    }

    override fun onSetAttachmentCaption(attachmentId: String, caption: String) {
        val trimmed = caption.trim()
        if (trimmed.isEmpty()) return
        scope.launch { if (detailRepository.updateAttachmentCaption(taskId, attachmentId, trimmed)) loadAttachments() }
    }

    override fun onDeleteOnDeviceAttachment(attachmentId: String) {
        scope.launch {
            onDeviceAttachments.delete(attachmentId)
            refreshOnDeviceAttachments()
        }
    }

    override suspend fun onDeviceAttachmentBytes(attachmentId: String): ByteArray? =
        onDeviceAttachments.bytes(attachmentId)

    /** The mutable extras the [state] combine folds in — the online-only sections + identity. */
    private data class Extras(
        val comments: List<Comment> = emptyList(),
        val commentsLoading: Boolean = false,
        val commentsError: Boolean = false,
        val isPostingComment: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val isUploadingAttachment: Boolean = false,
        val onDeviceAttachments: List<OnDeviceAttachment> = emptyList(),
        val currentUserId: UserId? = null,
    )
}

/**
 * Builds the recursive subtask tree rooted at [rootId] from the full cached Task list — direct
 * children are the live (non-deleted) rows whose `parentId == rootId`, ordered by `sequence`, each
 * recursively carrying its own subtree. Pure and local: the children are already cached from the Task
 * list pull, so the tree needs no extra network (ADR-0001).
 */
internal fun subtreeOf(rootId: TaskId, all: List<Task>): List<SubtaskNode> {
    val byParent = all.filterNot { it.isDeleted }.groupBy { it.parentId }
    fun nodesUnder(parent: TaskId): List<SubtaskNode> =
        (byParent[parent].orEmpty())
            .sortedBy { it.sequence }
            .map { SubtaskNode(it, nodesUnder(it.id)) }
    return nodesUnder(rootId)
}

/** Flattens a subtask tree to the list of its Tasks (for progress counting). */
internal fun List<SubtaskNode>.flatten(): List<Task> =
    flatMap { listOf(it.task) + it.children.flatten() }

/**
 * The clock a picked deadline DUE date lands on when the Task has no `deadlineTimeOfDay` of its own —
 * start-of-day (00:00), so `date.atTime(this).toInstant(zone)` equals `date.atStartOfDayIn(zone)`, the
 * exact bare-date behavior the create form (`NewState.toPayload`) produces.
 */
private val DEFAULT_DEADLINE_TIME: LocalTime = LocalTime(0, 0)

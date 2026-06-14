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
import kotlin.coroutines.CoroutineContext

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
    val currentUserId: UserId? = null,
)

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
    // The online-only comments + attachments source. Defaults to an empty no-op so tests that don't
    // care about the detail sections (and offline construction) build without supplying it.
    private val detailRepository: TaskDetailRepository = TaskDetailRepository.NONE,
    // The online-only create seam for "add subtask" (parent, title) — wired from the shell's create
    // command. Defaults to a no-op for the same reason as the editors above.
    private val createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
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
                currentUserId = ex.currentUserId,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TaskDetailState(isHydrating = true))

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
        loadComments()
    }

    private fun loadAttachments() {
        scope.launch {
            val attachments = detailRepository.attachments(taskId)
            // null = couldn't load; keep whatever we already have rather than blanking the list.
            extras.update { it.copy(attachments = attachments ?: it.attachments) }
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

    /** The mutable extras the [state] combine folds in — the online-only sections + identity. */
    private data class Extras(
        val comments: List<Comment> = emptyList(),
        val commentsLoading: Boolean = false,
        val commentsError: Boolean = false,
        val isPostingComment: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val isUploadingAttachment: Boolean = false,
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

package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.data.task.TaskDetailRepository
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.update
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun TestScope.taskDetailComponent(
    id: TaskId,
    repo: FakeTaskRepository,
    output: (TaskDetailComponent.Output) -> Unit = {},
    editor: WorkingStateEditor = WorkingStateEditor.NONE,
    detail: TaskDetailRepository = TaskDetailRepository.NONE,
    createSubtask: suspend (TaskId, String) -> Unit = { _, _ -> },
) = DefaultTaskDetailComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    taskId = id,
    taskRepository = repo,
    output = output,
    workingStateEditor = editor,
    detailRepository = detail,
    createSubtask = createSubtask,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

/** In-memory [TaskDetailRepository] for the detail-section tests; records writes, re-serves reads. */
private class FakeTaskDetailRepository(
    var commentsResult: List<Comment>? = emptyList(),
    var attachmentsResult: List<Attachment>? = emptyList(),
    var uploadResult: Boolean = true,
    private val userId: UserId? = UserId("me"),
) : TaskDetailRepository {
    var commentsCalls = 0
        private set
    var attachmentsCalls = 0
        private set
    val posted = mutableListOf<Pair<TaskId, String>>()
    val edited = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()
    val uploaded = mutableListOf<Pair<TaskId, List<AttachmentUpload>>>()
    val deletedAttachments = mutableListOf<Pair<TaskId, String>>()
    val captioned = mutableListOf<Pair<String, String>>()

    override suspend fun comments(taskId: TaskId): List<Comment>? { commentsCalls++; return commentsResult }
    override suspend fun postComment(taskId: TaskId, body: String): Boolean { posted += taskId to body; return true }
    override suspend fun editComment(commentId: String, body: String): Boolean { edited += commentId to body; return true }
    override suspend fun deleteComment(commentId: String): Boolean { deleted += commentId; return true }
    override suspend fun attachments(taskId: TaskId): List<Attachment>? { attachmentsCalls++; return attachmentsResult }
    override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean {
        uploaded += taskId to files
        return uploadResult
    }
    override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean {
        deletedAttachments += taskId to attachmentId
        return true
    }
    override suspend fun updateAttachmentCaption(taskId: TaskId, attachmentId: String, caption: String): Boolean {
        captioned += attachmentId to caption
        return true
    }
    override suspend fun currentUserId(): UserId? = userId
}

private fun comment(id: String, body: String, by: String = "me") = Comment(
    id = id,
    taskId = TaskId("a"),
    body = body,
    createdBy = UserId(by),
    createdAt = Instant.parse("2026-04-17T10:00:00Z"),
)

/** Records the working-state edits the detail issues and applies them to [repo]'s flow (optimistic). */
private class RecordingEditor(private val repo: FakeTaskRepository) : WorkingStateEditor {
    val calls = mutableListOf<Triple<TaskId, WorkingState, Task?>>()
    override suspend fun setWorkingState(id: TaskId, target: WorkingState, current: Task?) {
        calls += Triple(id, target, current)
        // Mirror the executor's pre-flight gate: a no-op transition writes nothing (ADR-0007).
        if (current?.workingState == target) return
        repo.tasks.update { list -> list.map { if (it.id == id) it.copy(workingState = target) else it } }
    }
}

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class TaskDetailComponentTest {

    @Test
    fun hydratesOnCreationAndExposesTheFullTask() = runTest {
        val summary = task("a", title = "A")
        val full = task("a", title = "A", hydration = HydrationState.Full, description = "the details")
        val repo = FakeTaskRepository(listOf(summary)).apply { hydrateResults = mapOf(TaskId("a") to full) }
        val component = taskDetailComponent(TaskId("a"), repo)

        component.state.test {
            // Drain to the settled state: hydrate has upgraded the row and finished.
            var item = awaitItem()
            while (item.isHydrating || item.task?.hydration != HydrationState.Full) item = awaitItem()
            assertEquals("the details", item.task.description)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(TaskId("a")), repo.hydrateCalls)
    }

    @Test
    fun closeShowTreeAndAddToPlanEmitIntents() = runTest {
        val outputs = mutableListOf<TaskDetailComponent.Output>()
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), outputs::add)
        advanceUntilIdle() // let the init hydrate settle so it doesn't interleave

        component.onShowTreeClicked()
        component.onAddToPlanClicked()
        component.onCloseClicked()

        assertEquals(
            listOf(
                TaskDetailComponent.Output.TreeRequested(TaskId("a")),
                TaskDetailComponent.Output.AddToPlanRequested(TaskId("a")),
                TaskDetailComponent.Output.Closed,
            ),
            outputs,
        )
    }

    @Test
    fun setWorkingStateIssuesTheEditWithTheCurrentRowAndUpdatesOptimistically() = runTest {
        val repo = FakeTaskRepository(listOf(task("a", workingState = WorkingState.Open)))
        val editor = RecordingEditor(repo)
        val component = taskDetailComponent(TaskId("a"), repo, editor = editor)
        advanceUntilIdle() // settle hydrate + the initial state emission

        component.state.test {
            // Drain to the observed Open row.
            var item = awaitItem()
            while (item.task?.workingState != WorkingState.Open) item = awaitItem()

            component.onSetWorkingState(WorkingState.InProgress)
            advanceUntilIdle()

            // The edit carried the currently-observed row as `current` for the pre-flight gate.
            assertEquals(1, editor.calls.size)
            assertEquals(TaskId("a"), editor.calls.single().first)
            assertEquals(WorkingState.InProgress, editor.calls.single().second)
            assertEquals(WorkingState.Open, editor.calls.single().third?.workingState)

            // The optimistic local update propagates back through the repository Flow.
            var updated = awaitItem()
            while (updated.task?.workingState != WorkingState.InProgress) updated = awaitItem()
            assertEquals(WorkingState.InProgress, updated.task.workingState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aStaleTransitionWritesNothing() = runTest {
        val repo = FakeTaskRepository(listOf(task("a", workingState = WorkingState.InProgress)))
        val editor = RecordingEditor(repo)
        val component = taskDetailComponent(TaskId("a"), repo, editor = editor)
        advanceUntilIdle()

        // Setting the state the Task is already in: the editor is asked, but the gate makes it a no-op.
        component.onSetWorkingState(WorkingState.InProgress)
        advanceUntilIdle()

        assertEquals(1, editor.calls.size)
        assertEquals(WorkingState.InProgress, repo.tasks.value.single().workingState) // unchanged
    }

    @Test
    fun buildsTheRecursiveSubtreeWithDoneProgress() = runTest {
        val repo = FakeTaskRepository(
            listOf(
                task("a"),
                task("b", parentId = "a", sequence = 1, workingState = WorkingState.Done),
                task("c", parentId = "b", sequence = 1),
            ),
        )
        val component = taskDetailComponent(TaskId("a"), repo)

        component.state.test {
            var item = awaitItem()
            while (item.subtaskTotal != 2) item = awaitItem()
            assertEquals(TaskId("b"), item.subtasks.single().task.id)
            assertEquals(TaskId("c"), item.subtasks.single().children.single().task.id)
            assertEquals(1, item.subtaskDone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun loadsCommentsAttachmentsAndIdentityOnCreation() = runTest {
        val att = Attachment(
            id = "att1", filename = "f.pdf", mime = "application/pdf", size = 1L,
            url = "u", createdBy = UserId("me"), createdAt = Instant.parse("2026-04-17T10:00:00Z"),
        )
        val detail = FakeTaskDetailRepository(
            commentsResult = listOf(comment("c1", "hi")),
            attachmentsResult = listOf(att),
        )
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)

        component.state.test {
            var item = awaitItem()
            while (item.comments.isEmpty() || item.attachments.isEmpty() || item.currentUserId == null) item = awaitItem()
            assertEquals("hi", item.comments.single().body)
            assertEquals("f.pdf", item.attachments.single().filename)
            assertEquals(UserId("me"), item.currentUserId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun postEditDeleteForwardAndReloadTheThread() = runTest {
        val detail = FakeTaskDetailRepository(commentsResult = emptyList())
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)
        advanceUntilIdle() // the init comment load (commentsCalls == 1)

        component.onPostComment("  ") // blank — ignored, no write
        component.onPostComment("hello")
        advanceUntilIdle()

        assertEquals(listOf(TaskId("a") to "hello"), detail.posted)
        assertEquals(2, detail.commentsCalls) // initial load + reload after the post

        component.onEditComment("c1", "fixed")
        component.onDeleteComment("c1")
        advanceUntilIdle()

        assertEquals(listOf("c1" to "fixed"), detail.edited)
        assertEquals(listOf("c1"), detail.deleted)
        assertEquals(4, detail.commentsCalls) // + reload after edit + reload after delete
    }

    @Test
    fun toggleSubtaskFlipsBetweenDoneAndOpen() = runTest {
        val repo = FakeTaskRepository(listOf(task("a"), task("b", parentId = "a", workingState = WorkingState.Open)))
        val editor = RecordingEditor(repo)
        val component = taskDetailComponent(TaskId("a"), repo, editor = editor)
        advanceUntilIdle()

        component.onToggleSubtaskDone(repo.tasks.value.first { it.id == TaskId("b") })
        advanceUntilIdle()
        assertEquals(WorkingState.Done, editor.calls.last().second)

        // Now Done (the RecordingEditor applied it) — toggling again returns it to Open.
        component.onToggleSubtaskDone(repo.tasks.value.first { it.id == TaskId("b") })
        advanceUntilIdle()
        assertEquals(WorkingState.Open, editor.calls.last().second)
    }

    @Test
    fun addSubtaskCallsTheCreateSeam_ignoringBlank() = runTest {
        val added = mutableListOf<Pair<TaskId, String>>()
        val component = taskDetailComponent(
            TaskId("a"),
            FakeTaskRepository(listOf(task("a"))),
            createSubtask = { id, title -> added += id to title },
        )
        advanceUntilIdle()

        component.onAddSubtask("   ") // blank — ignored
        component.onAddSubtask("New step")
        advanceUntilIdle()

        assertEquals(listOf(TaskId("a") to "New step"), added)
    }

    @Test
    fun addAttachmentsForwardsAndReloads_ignoringEmpty() = runTest {
        val detail = FakeTaskDetailRepository()
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)
        advanceUntilIdle() // the init attachment load (attachmentsCalls == 1)

        component.onAddAttachments(emptyList()) // ignored — no upload, no reload
        component.onAddAttachments(listOf(AttachmentUpload("f.pdf", "application/pdf", byteArrayOf(1, 2))))
        advanceUntilIdle()

        assertEquals(1, detail.uploaded.size)
        assertEquals(TaskId("a"), detail.uploaded.single().first)
        assertEquals(2, detail.attachmentsCalls) // initial load + reload after the upload
    }

    @Test
    fun addAttachments_uploadFailure_doesNotReload() = runTest {
        val detail = FakeTaskDetailRepository(uploadResult = false)
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)
        advanceUntilIdle()

        component.onAddAttachments(listOf(AttachmentUpload("f.pdf", "application/pdf", byteArrayOf(1))))
        advanceUntilIdle()

        assertEquals(1, detail.uploaded.size)
        assertEquals(1, detail.attachmentsCalls) // initial load only — a failed upload re-fetches nothing
    }

    @Test
    fun deleteAttachmentForwardsAndReloads() = runTest {
        val detail = FakeTaskDetailRepository()
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)
        advanceUntilIdle()

        component.onDeleteAttachment("att1")
        advanceUntilIdle()

        assertEquals(listOf(TaskId("a") to "att1"), detail.deletedAttachments)
        assertEquals(2, detail.attachmentsCalls) // initial load + reload after the delete
    }

    @Test
    fun setAttachmentCaptionForwardsAndReloads_ignoringBlank() = runTest {
        val detail = FakeTaskDetailRepository()
        val component = taskDetailComponent(TaskId("a"), FakeTaskRepository(listOf(task("a"))), detail = detail)
        advanceUntilIdle()

        component.onSetAttachmentCaption("att1", "   ") // blank — ignored, no write/reload
        component.onSetAttachmentCaption("att1", "  Receipt  ") // trimmed
        advanceUntilIdle()

        assertEquals(listOf("att1" to "Receipt"), detail.captioned)
        assertEquals(2, detail.attachmentsCalls) // initial load + reload after the caption set
    }
}

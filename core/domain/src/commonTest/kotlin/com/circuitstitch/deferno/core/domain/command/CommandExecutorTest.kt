package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The single binding surface's dispatch (ADR-0007, #26): [CommandExecutor] routes a pure-data
 * [Command] to exactly one `core:data` writer call, gating first on [CommandKind.enabledFor]. The intent
 * → action table, in the style of `core:data`'s `OutboxTaskWriterTest`/`MutationTest`, against the
 * recording [FakeTaskWriter]/[FakePlanWriter] (ADR-0006 JVM-fast path).
 */
class CommandExecutorTest {

    private val id = TaskId("t1")

    private fun executor(
        task: FakeTaskWriter,
        plan: FakePlanWriter,
        create: FakeCreateWriter = FakeCreateWriter(),
    ) = CommandExecutor(task, plan, create)

    @Test
    fun bindsEveryTaskCommandToTheRightWriterCallAndArgs() = runTest {
        val tw = FakeTaskWriter()
        val pw = FakePlanWriter()
        val ex = executor(tw, pw)

        // null current → the enablement gate is skipped, so every command dispatches.
        ex.execute(OpenTask(id))
        ex.execute(StartTask(id))
        ex.execute(SendTaskToReview(id))
        ex.execute(CompleteTask(id))
        ex.execute(ReopenTask(id))
        ex.execute(DropTask(id))
        ex.execute(RenameTask(id, "renamed"))
        ex.execute(SetTaskDeadline(id, SAMPLE_DEADLINE))
        ex.execute(ClearTaskDeadline(id))
        ex.execute(SetTaskDescription(id, "described"))
        ex.execute(ClearTaskDescription(id))
        ex.execute(SetTaskLabels(id, listOf("home")))
        ex.execute(SetTaskPinned(id, pinned = true))
        ex.execute(DeleteTask(id))

        assertEquals(
            listOf(
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Open),
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.InProgress),
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.InReview),
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Done),
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Open),
                FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Dropped),
                FakeTaskWriter.Call.Rename(id, "renamed"),
                FakeTaskWriter.Call.SetDeadline(id, SAMPLE_DEADLINE),
                FakeTaskWriter.Call.ClearDeadline(id),
                FakeTaskWriter.Call.SetDescription(id, "described"),
                FakeTaskWriter.Call.ClearDescription(id),
                FakeTaskWriter.Call.SetLabels(id, listOf("home")),
                FakeTaskWriter.Call.SetPinned(id, true),
                FakeTaskWriter.Call.Delete(id),
            ),
            tw.calls,
        )
        assertTrue(pw.calls.isEmpty(), "task commands must not touch the plan writer")
    }

    @Test
    fun bindsEveryPlanCommandToTheRightWriterCallAndArgs() = runTest {
        val tw = FakeTaskWriter()
        val pw = FakePlanWriter()
        val ex = executor(tw, pw)
        val id2 = TaskId("t2")

        ex.execute(AddToPlan(id, SAMPLE_DATE, SAMPLE_TZ))
        ex.execute(RemoveFromPlan(id, SAMPLE_DATE, SAMPLE_TZ))
        ex.execute(ReorderPlan(listOf(id2, id), SAMPLE_DATE, SAMPLE_TZ))

        assertEquals(
            listOf(
                FakePlanWriter.Call.Add(id, SAMPLE_DATE, SAMPLE_TZ),
                FakePlanWriter.Call.Remove(id, SAMPLE_DATE, SAMPLE_TZ),
                FakePlanWriter.Call.Reorder(listOf(id2, id), SAMPLE_DATE, SAMPLE_TZ),
            ),
            pw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "plan commands must not touch the task writer")
    }

    @Test
    fun deleteCarriesNoTimestampTheWriterOwnsNow() = runTest {
        val tw = FakeTaskWriter()
        val ex = executor(tw, FakePlanWriter())

        ex.execute(DeleteTask(id))

        // Regression guard: the command is just the id — the tombstone's `deletedAt` is the writer's clock.
        assertEquals(listOf<FakeTaskWriter.Call>(FakeTaskWriter.Call.Delete(id)), tw.calls)
    }

    @Test
    fun anEnabledCommandDispatchesAndReturnsAccepted() = runTest {
        val tw = FakeTaskWriter()
        val ex = executor(tw, FakePlanWriter())

        val result = ex.execute(CompleteTask(id), current = task(workingState = WorkingState.Open))

        assertEquals(CommandResult.Accepted(CommandKind.CompleteTask), result)
        assertEquals(listOf<FakeTaskWriter.Call>(FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Done)), tw.calls)
    }

    @Test
    fun aNullCurrentSkipsTheGateAndStillDispatches() = runTest {
        val tw = FakeTaskWriter()
        val ex = executor(tw, FakePlanWriter())

        // An offline write to an uncached row is never blocked — matches OutboxTaskWriter's behaviour.
        val result = ex.execute(CompleteTask(id), current = null)

        assertEquals(CommandResult.Accepted(CommandKind.CompleteTask), result)
        assertEquals(listOf<FakeTaskWriter.Call>(FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Done)), tw.calls)
    }

    @Test
    fun aStaleCommandIsRejectedNotApplicableAndWritesNothing() = runTest {
        val tw = FakeTaskWriter()
        val pw = FakePlanWriter()
        val ex = executor(tw, pw)

        // Each verb offered by a stale menu against a state it can't act on (the gate fires pre-write).
        assertEquals(
            CommandResult.Rejected(CommandKind.StartTask, RejectionReason.NotApplicable),
            ex.execute(StartTask(id), current = task(workingState = WorkingState.InProgress)),
        )
        assertEquals(
            CommandResult.Rejected(CommandKind.SendTaskToReview, RejectionReason.NotApplicable),
            ex.execute(SendTaskToReview(id), current = task(workingState = WorkingState.InReview)),
        )
        assertEquals(
            CommandResult.Rejected(CommandKind.CompleteTask, RejectionReason.NotApplicable),
            ex.execute(CompleteTask(id), current = task(workingState = WorkingState.Done)),
        )
        assertEquals(
            CommandResult.Rejected(CommandKind.ReopenTask, RejectionReason.NotApplicable),
            ex.execute(ReopenTask(id), current = task(workingState = WorkingState.Open)),
        )
        assertEquals(
            CommandResult.Rejected(CommandKind.DropTask, RejectionReason.NotApplicable),
            ex.execute(DropTask(id), current = task(workingState = WorkingState.Dropped)),
        )
        assertEquals(
            CommandResult.Rejected(CommandKind.DeleteTask, RejectionReason.NotApplicable),
            ex.execute(DeleteTask(id), current = task(deleted = true)),
        )

        assertTrue(tw.calls.isEmpty(), "a rejected command must perform no write")
        assertTrue(pw.calls.isEmpty())
    }

    @Test
    fun movingToOpenFromANonTerminalStateActuallyWritesAndIsNotRejected() = runTest {
        // Regression (#73 follow-up): the Tasks-detail five-state editor renders "Open" as always
        // tappable, so the user must be able to move to Open from ANY non-Open state — including the
        // non-terminal InProgress / InReview. The full real path: taskCommandFor(Open) → execute(current).
        // It must dispatch setWorkingState(Open), NOT be rejected NotApplicable as a silent no-op.
        for (from in listOf(WorkingState.InProgress, WorkingState.InReview, WorkingState.Done, WorkingState.Dropped)) {
            val tw = FakeTaskWriter()
            val ex = executor(tw, FakePlanWriter())

            val result = ex.execute(taskCommandFor(id, WorkingState.Open), current = task(workingState = from))

            assertEquals(
                CommandResult.Accepted(taskCommandFor(id, WorkingState.Open).kind),
                result,
                "moving to Open from $from must be accepted, not a silent no-op",
            )
            assertEquals(
                listOf<FakeTaskWriter.Call>(FakeTaskWriter.Call.SetWorkingState(id, WorkingState.Open)),
                tw.calls,
                "moving to Open from $from must write setWorkingState(Open)",
            )
        }
    }

    @Test
    fun movingToOpenWhenAlreadyOpenIsAStaleNoOp() = runTest {
        // The only no-op transition for Open is the one where the Task is already Open (#73): the
        // pre-flight gate rejects it before any write, like every other lifecycle verb's self-transition.
        val tw = FakeTaskWriter()
        val ex = executor(tw, FakePlanWriter())

        val result = ex.execute(taskCommandFor(id, WorkingState.Open), current = task(workingState = WorkingState.Open))

        assertEquals(RejectionReason.NotApplicable, (result as CommandResult.Rejected).reason)
        assertTrue(tw.calls.isEmpty(), "a no-op self-transition to Open must write nothing")
    }

    @Test
    fun everyCommandKindDispatchesToTheRightWriterAndAcceptsItsKind() = runTest {
        // Catalog-wide integrity: drives a sample of EACH kind through the exhaustive `when`. Asserting
        // per-writer counts (against the catalog's task/plan/create partition) — not just the total —
        // means a kind that is unbound (0 calls), double-dispatched, or *mis-routed* to the wrong writer
        // all fail here, before any becomes a hole. (The online-only create/convert kinds route to the
        // CreateWriter, whose default fake returns Created → Accepted.)
        for (kind in CommandKind.entries) {
            val tw = FakeTaskWriter()
            val pw = FakePlanWriter()
            val cw = FakeCreateWriter(result = CreateResultFixtures.createdFor(kind))

            val result = executor(tw, pw, cw).execute(sampleCommand(kind)) // null current → gate skipped

            assertEquals(CommandResult.Accepted(kind), result, "kind $kind should be Accepted")
            val isPlan = kind in CommandKind.planKinds
            val isCreate = kind in CommandKind.createKinds
            assertEquals(if (isPlan) 1 else 0, pw.calls.size, "kind $kind plan-writer calls")
            assertEquals(if (isPlan || isCreate) 0 else 1, tw.calls.size, "kind $kind task-writer calls")
            assertEquals(if (isCreate) 1 else 0, cw.calls.size, "kind $kind create-writer calls")
        }
    }

    @Test
    fun createItemWhenOnlineDispatchesToTheCreateWriterAndIsAccepted() = runTest {
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Created(
            com.circuitstitch.deferno.core.model.ItemKind.Task, "server-1",
        ))
        val result = executor(FakeTaskWriter(), FakePlanWriter(), cw)
            .execute(CreateItem(CreateItem.Payload.Task(com.circuitstitch.deferno.core.network.dto.CreateTaskPayload(title = "x"))))

        assertEquals(CommandResult.Accepted(CommandKind.CreateItem), result)
        assertEquals(listOf("createTask"), cw.calls)
    }

    @Test
    fun createItemWhenOfflineReturnsOfflineAndTheTaskWriterIsUntouched() = runTest {
        // ADR-0016: the writer owns the connectivity gate; the executor faithfully surfaces Offline (not
        // a false Accepted), and the offline-first task/plan writers are never touched (nothing enqueued).
        val tw = FakeTaskWriter()
        val pw = FakePlanWriter()
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Offline)

        val result = executor(tw, pw, cw)
            .execute(CreateItem(CreateItem.Payload.Habit(com.circuitstitch.deferno.core.network.dto.CreateHabitPayload(
                title = "stretch",
                recurrence = com.circuitstitch.deferno.core.network.dto.RecurrenceDto("daily"),
            ))))

        assertEquals(CommandResult.Offline(CommandKind.CreateItem), result)
        assertTrue(tw.calls.isEmpty(), "offline create must not enqueue via the task writer")
        assertTrue(pw.calls.isEmpty())
    }

    @Test
    fun convertItemDispatchesToTheConvertWriter() = runTest {
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Created(
            com.circuitstitch.deferno.core.model.ItemKind.Habit, "item-1",
        ))
        val result = executor(FakeTaskWriter(), FakePlanWriter(), cw)
            .execute(ConvertItem("item-1", com.circuitstitch.deferno.core.model.ItemKind.Task,
                com.circuitstitch.deferno.core.network.dto.ConvertItemPayload(type = "habit")))

        assertEquals(CommandResult.Accepted(CommandKind.ConvertItem), result)
        assertEquals(listOf("convert:item-1:Task"), cw.calls)
    }

    @Test
    fun createItemServerRejectionSurfacesAsFailed() = runTest {
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Failed("title required"))
        val result = executor(FakeTaskWriter(), FakePlanWriter(), cw)
            .execute(CreateItem(CreateItem.Payload.Task(com.circuitstitch.deferno.core.network.dto.CreateTaskPayload(title = ""))))

        assertEquals(CommandResult.Failed(CommandKind.CreateItem, "title required"), result)
    }
}

/** Per-kind [CreateResult] fixture for the catalog-wide test (the create kinds need a Created outcome). */
private object CreateResultFixtures {
    fun createdFor(kind: CommandKind): com.circuitstitch.deferno.core.data.create.CreateResult =
        com.circuitstitch.deferno.core.data.create.CreateResult.Created(
            com.circuitstitch.deferno.core.model.ItemKind.Task,
            "server-${kind.name}",
        )
}

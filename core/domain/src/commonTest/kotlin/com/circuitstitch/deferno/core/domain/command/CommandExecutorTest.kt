package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.task.BlockedByResult
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
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
        occurrence: FakeOccurrenceWriter = FakeOccurrenceWriter(),
        settings: FakeSettingsWriter = FakeSettingsWriter(),
        item: FakeItemWriter = FakeItemWriter(),
        definition: FakeDefinitionWriter = FakeDefinitionWriter(),
        blockedBy: FakeBlockedByWriter = FakeBlockedByWriter(),
    ) = CommandExecutor(task, plan, create, occurrence, settings, item, definition, blockedBy)

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
        ex.execute(SetTaskDeadlineTime(id, LocalTime(9, 0)))
        ex.execute(SetTaskDeadlineTime(id, null))
        ex.execute(SetTaskDescription(id, "described"))
        ex.execute(ClearTaskDescription(id))
        ex.execute(SetTaskLabels(id, listOf("home")))
        ex.execute(SetTaskPinned(id, pinned = true))
        ex.execute(SetTaskTargetDate(id, SAMPLE_TARGET_DATE))
        ex.execute(SetTaskTargetDate(id, null))
        ex.execute(SetTaskPriority(id, Priority.Backlog))
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
                FakeTaskWriter.Call.SetDeadlineTime(id, LocalTime(9, 0)),
                FakeTaskWriter.Call.SetDeadlineTime(id, null),
                FakeTaskWriter.Call.SetDescription(id, "described"),
                FakeTaskWriter.Call.ClearDescription(id),
                FakeTaskWriter.Call.SetLabels(id, listOf("home")),
                FakeTaskWriter.Call.SetPinned(id, true),
                FakeTaskWriter.Call.SetTargetDate(id, SAMPLE_TARGET_DATE),
                FakeTaskWriter.Call.SetTargetDate(id, null),
                FakeTaskWriter.Call.SetPriority(id, Priority.Backlog),
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
        val ref = PlanItemRef(id.value, ItemKind.Task)
        val ref2 = PlanItemRef("t2", ItemKind.Task)

        ex.execute(AddToPlan(ref, SAMPLE_DATE, SAMPLE_TZ))
        ex.execute(RemoveFromPlan(id.value, SAMPLE_DATE, SAMPLE_TZ))
        ex.execute(ReorderPlan(listOf(ref2, ref), SAMPLE_DATE, SAMPLE_TZ))

        assertEquals(
            listOf(
                FakePlanWriter.Call.Add(ref, SAMPLE_DATE, SAMPLE_TZ),
                FakePlanWriter.Call.Remove(id.value, SAMPLE_DATE, SAMPLE_TZ),
                FakePlanWriter.Call.Reorder(listOf(ref2, ref), SAMPLE_DATE, SAMPLE_TZ),
            ),
            pw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "plan commands must not touch the task writer")
    }

    /**
     * The kind survives the executor untouched (#385). [ReorderPlan] is the drag-drop result, so it
     * carries whatever the Plan is showing — a Habit among Tasks. Coercing that ref to Task on the way
     * through would write the local ordering under a kind the next resolve can't find it by, dropping
     * the row from the client's own optimistic write.
     */
    @Test
    fun forwardsANonTaskRefWithItsKindIntact() = runTest {
        val pw = FakePlanWriter()
        val ex = executor(FakeTaskWriter(), pw)
        val habit = PlanItemRef("h1", ItemKind.Habit)
        val task = PlanItemRef("t1", ItemKind.Task)

        ex.execute(AddToPlan(habit, SAMPLE_DATE, SAMPLE_TZ))
        ex.execute(ReorderPlan(listOf(habit, task), SAMPLE_DATE, SAMPLE_TZ))

        assertEquals(
            listOf(
                FakePlanWriter.Call.Add(habit, SAMPLE_DATE, SAMPLE_TZ),
                FakePlanWriter.Call.Reorder(listOf(habit, task), SAMPLE_DATE, SAMPLE_TZ),
            ),
            pw.calls,
        )
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
            val ow = FakeOccurrenceWriter()
            val sw = FakeSettingsWriter()
            val iw = FakeItemWriter()
            val dw = FakeDefinitionWriter()
            val bw = FakeBlockedByWriter()

            val result = executor(tw, pw, cw, ow, sw, iw, dw, bw).execute(sampleCommand(kind)) // null current → gate skipped

            // Create/convert surface the created id (#211); every other kind has no new id (itemId = null).
            val expectedId = if (kind in CommandKind.createKinds) "server-${kind.name}" else null
            assertEquals(CommandResult.Accepted(kind, expectedId), result, "kind $kind should be Accepted")
            val isPlan = kind in CommandKind.planKinds
            val isCreate = kind in CommandKind.createKinds
            val isOccurrence = kind in CommandKind.occurrenceKinds
            val isSettings = kind in CommandKind.settingsKinds
            val isItem = kind in CommandKind.itemKinds
            val isDefinition = kind in CommandKind.definitionKinds
            val isBlockedBy = kind == CommandKind.SetTaskBlockedBy
            assertEquals(if (isPlan) 1 else 0, pw.calls.size, "kind $kind plan-writer calls")
            assertEquals(
                if (isPlan || isCreate || isOccurrence || isSettings || isItem || isDefinition || isBlockedBy) 0 else 1,
                tw.calls.size,
                "kind $kind task-writer calls",
            )
            assertEquals(if (isCreate) 1 else 0, cw.calls.size, "kind $kind create-writer calls")
            assertEquals(if (isOccurrence) 1 else 0, ow.calls.size, "kind $kind occurrence-writer calls")
            assertEquals(if (isSettings) 1 else 0, sw.calls.size, "kind $kind settings-writer calls")
            assertEquals(if (isItem) 1 else 0, iw.calls.size, "kind $kind item-writer calls")
            assertEquals(if (isDefinition) 1 else 0, dw.calls.size, "kind $kind definition-writer calls")
            assertEquals(if (isBlockedBy) 1 else 0, bw.calls.size, "kind $kind blockedBy-writer calls")
        }
    }

    @Test
    fun setTaskBlockedByRoutesToTheBlockedByWriterAndSurfacesItsVerdict() = runTest {
        // #291: the dependency-edge write is online-only (the convert posture) — the executor returns
        // the writer's own verdict, mapping ids → item-only BlockedByRefs (occurrence is a follow-up).
        val tw = FakeTaskWriter()
        val bw = FakeBlockedByWriter()
        val ex = executor(tw, FakePlanWriter(), blockedBy = bw)

        assertEquals(
            CommandResult.Accepted(CommandKind.SetTaskBlockedBy),
            ex.execute(SetTaskBlockedBy(id, listOf("b1", "b2"))),
        )
        assertEquals(
            listOf(FakeBlockedByWriter.Call(id, listOf(BlockedByRef("b1"), BlockedByRef("b2")))),
            bw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "a blockedBy set must not touch the outbox task writer")

        // Offline → structured Offline (nothing applied or enqueued)…
        bw.result = BlockedByResult.Offline
        assertEquals(CommandResult.Offline(CommandKind.SetTaskBlockedBy), ex.execute(SetTaskBlockedBy(id, emptyList())))
        // …and a server 400 (cycle / cross-org) surfaces its message as Failed, never a blind Accepted.
        bw.result = BlockedByResult.Failed("cannot add a blocked_by edge that would form a cycle")
        assertEquals(
            CommandResult.Failed(CommandKind.SetTaskBlockedBy, "cannot add a blocked_by edge that would form a cycle"),
            ex.execute(SetTaskBlockedBy(id, listOf("b1"))),
        )
    }

    @Test
    fun moveItemRoutesToTheItemWriterWithItsDestinationAndIndex() = runTest {
        // ADR-0049 #228: the cross-kind move is offline-first (optimistic apply + enqueue), so it is
        // Accepted, routes to the ItemWriter alone, and forwards the raw id / new parent / insertion index.
        val tw = FakeTaskWriter()
        val iw = FakeItemWriter()
        val ex = executor(tw, FakePlanWriter(), item = iw)

        assertEquals(CommandResult.Accepted(CommandKind.MoveItem), ex.execute(MoveItem("h1", newParentId = "t1", position = 3)))
        // A move to root carries an explicit null parent.
        assertEquals(CommandResult.Accepted(CommandKind.MoveItem), ex.execute(MoveItem("h1", newParentId = null, position = 0)))

        assertEquals(
            listOf<FakeItemWriter.Call>(
                FakeItemWriter.Call.Move("h1", "t1", 3),
                FakeItemWriter.Call.Move("h1", null, 0),
            ),
            iw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "a move must not touch the task writer")
    }

    @Test
    fun deleteItemRoutesToTheItemWriterWithTheRawIdAndNoKind() = runTest {
        // #389: the kind-neutral soft delete. It is offline-first (optimistic tombstone + enqueue), so
        // it is Accepted, and it forwards ONLY the raw Item id — the server route resolves the kind and
        // deletes the whole Series chain. The same command deletes a Habit and a Task, which is what
        // lets the Item tree offer one Delete for every row.
        val tw = FakeTaskWriter()
        val iw = FakeItemWriter()
        val ex = executor(tw, FakePlanWriter(), item = iw)

        assertEquals(CommandResult.Accepted(CommandKind.DeleteItem), ex.execute(DeleteItem("h1")))
        assertEquals(CommandResult.Accepted(CommandKind.DeleteItem), ex.execute(DeleteItem("t1")))

        assertEquals(
            listOf<FakeItemWriter.Call>(FakeItemWriter.Call.Delete("h1"), FakeItemWriter.Call.Delete("t1")),
            iw.calls,
        )
        // The regression this guards: routing a recurring row's delete to the Task seam sends
        // `DELETE tasks/{habitId}`, which 404s — and the outbox maps 404 to success, so the write
        // evaporates while the optimistic tombstone sticks. Silent divergence, no error anywhere.
        assertTrue(tw.calls.isEmpty(), "the kind-neutral delete must never reach the Task writer")
    }

    @Test
    fun theKindNeutralDeleteIsNotGatedByATombstonedTaskRow() = runTest {
        // [DeleteTask] is rejected NotApplicable once the cached row is tombstoned; [DeleteItem] is not
        // gated at all (the gate is Task-typed and blind to the recurring rows it exists for), so even a
        // mistakenly-supplied deleted Task row must not swallow it. Pins the deliberate asymmetry.
        val iw = FakeItemWriter()
        val ex = executor(FakeTaskWriter(), FakePlanWriter(), item = iw)

        val result = ex.execute(DeleteItem("h1"), current = task(deleted = true))

        assertEquals(CommandResult.Accepted(CommandKind.DeleteItem), result)
        assertEquals(listOf<FakeItemWriter.Call>(FakeItemWriter.Call.Delete("h1")), iw.calls)
    }

    @Test
    fun setDefinitionStateRoutesToTheDefinitionWriterWithItsIdKindAndTarget() = runTest {
        // #299: the recurring "light switch" is offline-first (optimistic apply + enqueue), so it is
        // Accepted, routes to the DefinitionWriter alone, and forwards the raw id / kind / target.
        val tw = FakeTaskWriter()
        val dw = FakeDefinitionWriter()
        val ex = executor(tw, FakePlanWriter(), definition = dw)

        assertEquals(
            CommandResult.Accepted(CommandKind.SetDefinitionState),
            ex.execute(SetDefinitionState("h1", ItemKind.Habit, DefinitionState.Archived)),
        )
        assertEquals(
            CommandResult.Accepted(CommandKind.SetDefinitionState),
            ex.execute(SetDefinitionState("c2", ItemKind.Chore, DefinitionState.Active)),
        )

        assertEquals(
            listOf<FakeDefinitionWriter.Call>(
                FakeDefinitionWriter.Call.SetState("h1", ItemKind.Habit, DefinitionState.Archived),
                FakeDefinitionWriter.Call.SetState("c2", ItemKind.Chore, DefinitionState.Active),
            ),
            dw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "a definition-state set must not touch the task writer")
    }

    @Test
    fun setDefinitionTargetDateRoutesToTheDefinitionWriterAndCarriesAnExplicitClear() = runTest {
        // #378: the recurring half of the soft target date. Offline-first (optimistic apply + enqueue),
        // so Accepted, and it forwards the raw id / kind / instant to the DefinitionWriter alone.
        val tw = FakeTaskWriter()
        val dw = FakeDefinitionWriter()
        val ex = executor(tw, FakePlanWriter(), definition = dw)

        assertEquals(
            CommandResult.Accepted(CommandKind.SetDefinitionTargetDate),
            ex.execute(SetDefinitionTargetDate("h1", ItemKind.Habit, SAMPLE_TARGET_DATE)),
        )
        // A null operand is the CLEAR, not "leave it alone": the command must reach the writer, which
        // emits an explicit JSON null. Dropping it here would make the clear a silent server no-op.
        assertEquals(
            CommandResult.Accepted(CommandKind.SetDefinitionTargetDate),
            ex.execute(SetDefinitionTargetDate("e3", ItemKind.Event, null)),
        )

        assertEquals(
            listOf<FakeDefinitionWriter.Call>(
                FakeDefinitionWriter.Call.SetTargetDate("h1", ItemKind.Habit, SAMPLE_TARGET_DATE),
                FakeDefinitionWriter.Call.SetTargetDate("e3", ItemKind.Event, null),
            ),
            dw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "a recurring target-date set must not touch the task writer")
    }

    @Test
    fun setDefinitionPriorityRoutesToTheDefinitionWriterWithEveryBucket() = runTest {
        // #378: the recurring half of the urgency bucket. Every bucket is a real value — there is no
        // null form — so Backlog and Normal are ordinary writes, not "clear the priority".
        val tw = FakeTaskWriter()
        val dw = FakeDefinitionWriter()
        val ex = executor(tw, FakePlanWriter(), definition = dw)

        for (priority in Priority.entries) {
            assertEquals(
                CommandResult.Accepted(CommandKind.SetDefinitionPriority),
                ex.execute(SetDefinitionPriority("c1", ItemKind.Chore, priority)),
            )
        }

        assertEquals(
            Priority.entries.map<Priority, FakeDefinitionWriter.Call> {
                FakeDefinitionWriter.Call.SetPriority("c1", ItemKind.Chore, it)
            },
            dw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "a recurring priority set must not touch the task writer")
    }

    @Test
    fun theRecurringFieldEditsNeverCollapseOntoTheirTaskTwins() = runTest {
        // The mis-routing this whole pair of kinds exists to prevent: a Habit's target date / priority
        // must NOT go through the TaskWriter (which would PATCH `tasks/{habitId}` — a 404 the outbox
        // maps to success, so the edit would vanish silently). Asserted as a per-writer census rather
        // than by inspecting operands, so a future "simplification" that reuses the Task seam fails here.
        val tw = FakeTaskWriter()
        val dw = FakeDefinitionWriter()
        val ex = executor(tw, FakePlanWriter(), definition = dw)

        ex.execute(SetDefinitionTargetDate("h1", ItemKind.Habit, SAMPLE_TARGET_DATE))
        ex.execute(SetDefinitionPriority("h1", ItemKind.Habit, Priority.Fire))
        // …while the Task twins still route to the Task seam, unchanged.
        ex.execute(SetTaskTargetDate(id, SAMPLE_TARGET_DATE))
        ex.execute(SetTaskPriority(id, Priority.Fire))

        assertEquals(2, dw.calls.size, "both recurring edits belong to the definition writer")
        assertEquals(
            listOf<FakeTaskWriter.Call>(
                FakeTaskWriter.Call.SetTargetDate(id, SAMPLE_TARGET_DATE),
                FakeTaskWriter.Call.SetPriority(id, Priority.Fire),
            ),
            tw.calls,
            "only the Task twins may reach the Task writer",
        )
    }

    @Test
    fun createItemDispatchesToTheCreateWriterAndIsAcceptedWithTheCreatedId() = runTest {
        // #185: create is offline-first — the writer optimistically inserts + enqueues and reports the
        // client id; the executor surfaces it as Accepted(itemId) (the id #211 attaches a recording to).
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Created(
            com.circuitstitch.deferno.core.model.ItemKind.Task, "client-1",
        ))
        val result = executor(FakeTaskWriter(), FakePlanWriter(), cw)
            .execute(CreateItem(CreateItem.Payload.Task(com.circuitstitch.deferno.core.network.dto.CreateTaskPayload(title = "x"))))

        assertEquals(CommandResult.Accepted(CommandKind.CreateItem, itemId = "client-1"), result)
        assertEquals(listOf("createTask"), cw.calls)
    }

    @Test
    fun convertItemWhenOfflineReturnsOfflineAndTheTaskWriterIsUntouched() = runTest {
        // ADR-0016: convert stays online-only — the writer owns the connectivity gate; the executor
        // faithfully surfaces Offline (not a false Accepted), and the offline-first task/plan writers
        // are never touched (nothing enqueued).
        val tw = FakeTaskWriter()
        val pw = FakePlanWriter()
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Offline)

        val result = executor(tw, pw, cw)
            .execute(ConvertItem("item-1", com.circuitstitch.deferno.core.model.ItemKind.Task,
                com.circuitstitch.deferno.core.network.dto.ConvertItemPayload(to = "habit")))

        assertEquals(CommandResult.Offline(CommandKind.ConvertItem), result)
        assertTrue(tw.calls.isEmpty(), "offline convert must not enqueue via the task writer")
        assertTrue(pw.calls.isEmpty())
    }

    @Test
    fun convertItemDispatchesToTheConvertWriter() = runTest {
        val cw = FakeCreateWriter(result = com.circuitstitch.deferno.core.data.create.CreateResult.Created(
            com.circuitstitch.deferno.core.model.ItemKind.Habit, "item-1",
        ))
        val result = executor(FakeTaskWriter(), FakePlanWriter(), cw)
            .execute(ConvertItem("item-1", com.circuitstitch.deferno.core.model.ItemKind.Task,
                com.circuitstitch.deferno.core.network.dto.ConvertItemPayload(to = "habit")))

        assertEquals(CommandResult.Accepted(CommandKind.ConvertItem, itemId = "item-1"), result)
        assertEquals(listOf("convert:item-1:Task"), cw.calls)
    }

    @Test
    fun occurrenceCommandsRouteToTheOccurrenceWriterOfflineFirst() = runTest {
        val tw = FakeTaskWriter()
        val ow = FakeOccurrenceWriter()
        val ex = executor(tw, FakePlanWriter(), occurrence = ow)

        // Mark / clear / reschedule are offline-first acts on an existing firing — Accepted, like the edits.
        assertEquals(CommandResult.Accepted(CommandKind.MarkOccurrence), ex.execute(MarkOccurrence("ce-1", com.circuitstitch.deferno.core.model.OccurrenceAction.Complete)))
        assertEquals(CommandResult.Accepted(CommandKind.ClearOccurrence), ex.execute(ClearOccurrence("ce-1")))
        assertEquals(CommandResult.Accepted(CommandKind.RescheduleOccurrence), ex.execute(RescheduleOccurrence("ce-1", SAMPLE_DATE)))

        assertEquals(
            listOf<FakeOccurrenceWriter.Call>(
                FakeOccurrenceWriter.Call.Mark("ce-1", com.circuitstitch.deferno.core.model.OccurrenceAction.Complete),
                FakeOccurrenceWriter.Call.Clear("ce-1"),
                FakeOccurrenceWriter.Call.Reschedule("ce-1", SAMPLE_DATE),
            ),
            ow.calls,
        )
        assertTrue(tw.calls.isEmpty(), "occurrence commands must not touch the task writer")
    }

    @Test
    fun settingsCommandsRouteToTheSettingsWriterOfflineFirst() = runTest {
        // Per-field User-setting verbs (#173), 1:1 with the SettingsWriter seam — offline-first like the
        // Task edits (the writer optimistically applies + enqueues), so each dispatch is Accepted.
        val tw = FakeTaskWriter()
        val sw = FakeSettingsWriter()
        val ex = executor(tw, FakePlanWriter(), settings = sw)

        assertEquals(CommandResult.Accepted(CommandKind.SetTheme), ex.execute(SetTheme(ThemeFamily.Mono, ThemeMode.Dark)))
        assertEquals(CommandResult.Accepted(CommandKind.SetTracking), ex.execute(SetTracking(enabled = true)))
        assertEquals(CommandResult.Accepted(CommandKind.SetDragAndDrop), ex.execute(SetDragAndDrop(enabled = false)))
        assertEquals(CommandResult.Accepted(CommandKind.SetDoneVisibility), ex.execute(SetDoneVisibility(259200L, null)))

        assertEquals(
            listOf<FakeSettingsWriter.Call>(
                FakeSettingsWriter.Call.SetTheme(ThemeFamily.Mono, ThemeMode.Dark),
                FakeSettingsWriter.Call.SetTracking(true),
                FakeSettingsWriter.Call.SetDragAndDrop(false),
                FakeSettingsWriter.Call.SetDoneVisibility(259200L, null),
            ),
            sw.calls,
        )
        assertTrue(tw.calls.isEmpty(), "settings commands must not touch the task writer")
    }

    @Test
    fun settingsCommandsPassTheEnablementGateRegardlessOfAnyCachedRow() = runTest {
        // A settings write targets the Account's settings bag, not a Task row (#173), so the pre-flight
        // enabledFor gate has no per-Task rule to apply: even a (mistakenly) supplied cached row in a
        // terminal, tombstoned state must not reject the write — the `else -> true` arm, like the
        // edit / organize kinds.
        val sw = FakeSettingsWriter()
        val ex = executor(FakeTaskWriter(), FakePlanWriter(), settings = sw)

        val result = ex.execute(
            SetTracking(enabled = true),
            current = task(workingState = WorkingState.Done, deleted = true),
        )

        assertEquals(CommandResult.Accepted(CommandKind.SetTracking), result)
        assertEquals(listOf<FakeSettingsWriter.Call>(FakeSettingsWriter.Call.SetTracking(true)), sw.calls)
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

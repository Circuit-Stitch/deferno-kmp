package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The enumerable catalog (ADR-0007): [CommandKind] is the single source of truth a palette / menu /
 * agent / OS intent binds to. These pin its public contract — stable ids, the destructive set, the
 * applicability matrix, and the task/plan partition — the pure logic the registry exists to define
 * "once in the core."
 */
class CommandKindTest {

    @Test
    fun commandIdsAreUniqueAndStable() {
        val ids = CommandKind.entries.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size, "command ids must be unique")

        // Pin the exact wire of every id: changing one is a contract break for anything already bound.
        assertEquals(
            mapOf(
                CommandKind.CompleteTask to "task.complete",
                CommandKind.ReopenTask to "task.reopen",
                CommandKind.DropTask to "task.drop",
                CommandKind.RenameTask to "task.rename",
                CommandKind.SetTaskDeadline to "task.set-deadline",
                CommandKind.ClearTaskDeadline to "task.clear-deadline",
                CommandKind.SetTaskDescription to "task.set-description",
                CommandKind.ClearTaskDescription to "task.clear-description",
                CommandKind.SetTaskLabels to "task.set-labels",
                CommandKind.SetTaskPinned to "task.set-pinned",
                CommandKind.DeleteTask to "task.delete",
                CommandKind.AddToPlan to "plan.add",
                CommandKind.RemoveFromPlan to "plan.remove",
                CommandKind.ReorderPlan to "plan.reorder",
                CommandKind.StartTask to "task.start",
                CommandKind.SendTaskToReview to "task.send-to-review",
                CommandKind.OpenTask to "task.open",
                CommandKind.CreateItem to "item.create",
                CommandKind.ConvertItem to "item.convert",
                CommandKind.MarkOccurrence to "occurrence.mark",
                CommandKind.ClearOccurrence to "occurrence.clear",
                CommandKind.RescheduleOccurrence to "occurrence.reschedule",
                CommandKind.SetTheme to "settings.set-theme",
                CommandKind.SetTracking to "settings.set-tracking",
                CommandKind.SetDragAndDrop to "settings.set-drag-and-drop",
                CommandKind.SetDoneVisibility to "settings.set-done-visibility",
                CommandKind.MoveItem to "item.move",
                CommandKind.SetDefinitionState to "definition.set-state",
                CommandKind.SetTaskBlockedBy to "task.set-blocked-by",
                CommandKind.SetTaskDeadlineTime to "task.set-deadline-time",
            ),
            CommandKind.entries.associateWith { it.id.value },
        )
    }

    @Test
    fun convertAndBlockedByAreTheOnlyOnlineOnlyKinds() {
        // #185: create became offline-first (the backend dedupes on the client-supplied id), so only
        // convert keeps the ADR-0016 online-only flag — joined by the dependency-edge write (#291),
        // whose 400 cycle/cross-org verdict must come back synchronously; every offline-first
        // create/edit/status/plan kind is not.
        assertEquals(
            setOf(CommandKind.ConvertItem, CommandKind.SetTaskBlockedBy),
            CommandKind.entries.filter { it.onlineOnly }.toSet(),
        )
        // Create + convert still group under the Create category / createKinds catalog.
        assertEquals(setOf(CommandKind.CreateItem, CommandKind.ConvertItem), CommandKind.createKinds.toSet())
    }

    @Test
    fun aBlankCommandIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { CommandId("") }
        assertFailsWith<IllegalArgumentException> { CommandId("   ") }
    }

    @Test
    fun deleteIsTheOnlyDestructiveKind() {
        assertEquals(listOf(CommandKind.DeleteTask), CommandKind.entries.filter { it.destructive })
    }

    @Test
    fun everyKindReportsItsCatalogKindFromItsInstance() {
        // The instance → catalog link the binding surface relies on (command.kind), over the whole catalog.
        for (kind in CommandKind.entries) {
            assertEquals(kind, sampleCommand(kind).kind)
        }
    }

    @Test
    fun enablementMatchesTheWorkingStateTransitionMatrix() {
        // A hand-written spec table with LITERAL expected values — deliberately not re-derived from the
        // SUT's own `!=`/`isTerminal` predicates, so an inverted rule mirrored into both files can't pass
        // green. Columns are the five working states in this fixed order:
        val states = listOf(
            WorkingState.Open,
            WorkingState.InProgress,
            WorkingState.InReview,
            WorkingState.Done,
            WorkingState.Dropped,
        )
        val expected = mapOf(
            //                            Open   InProg InRev  Done   Dropped
            // OpenTask (#73 follow-up): enabled from EVERY non-Open state — the editor's Open chip must
            // not be a silent no-op from the non-terminal In-progress / In-review.
            CommandKind.OpenTask to listOf(false, true, true, true, true),
            CommandKind.StartTask to listOf(true, false, true, true, true),
            CommandKind.SendTaskToReview to listOf(true, true, false, true, true),
            CommandKind.CompleteTask to listOf(true, true, true, false, true),
            // ReopenTask keeps its narrower terminal-only gate (Done/Dropped) — unchanged.
            CommandKind.ReopenTask to listOf(false, false, false, true, true),
            CommandKind.DropTask to listOf(true, true, true, true, false),
        )
        for ((kind, row) in expected) {
            states.forEachIndexed { i, state ->
                assertEquals(row[i], kind.enabledFor(task(workingState = state)), "$kind @ $state")
            }
        }
    }

    @Test
    fun nonLifecycleKindsAlwaysApplyInEveryWorkingState() {
        // Every kind that is NOT a status verb or the state-gated delete applies regardless of the Task's
        // state — pinned here against a real (non-null) task across the whole catalog, so RemoveFromPlan /
        // ReorderPlan / the edit + schedule + organize kinds are each covered, not just a sample.
        val gated = setOf(
            CommandKind.OpenTask,
            CommandKind.StartTask,
            CommandKind.SendTaskToReview,
            CommandKind.CompleteTask,
            CommandKind.ReopenTask,
            CommandKind.DropTask,
            CommandKind.DeleteTask,
        )
        val alwaysOn = CommandKind.entries - gated
        for (state in WorkingState.entries) {
            for (kind in alwaysOn) {
                assertTrue(kind.enabledFor(task(workingState = state)), "$kind should always apply @ $state")
            }
        }
    }

    @Test
    fun deleteIsEnabledUntilTheRowIsTombstoned() {
        assertTrue(CommandKind.DeleteTask.enabledFor(task(deleted = false)))
        assertFalse(CommandKind.DeleteTask.enabledFor(task(deleted = true)))
    }

    @Test
    fun everyKindIsEnabledForAnUnknownRow() {
        // null = uncached/unknown → never block (the offline write still enqueues + reconciles).
        for (kind in CommandKind.entries) {
            assertTrue(kind.enabledFor(null), "$kind should be enabled for a null/uncached task")
        }
    }

    @Test
    fun taskPlanCreateOccurrenceAndSettingsKindsPartitionTheCatalog() {
        val partitions = listOf(
            CommandKind.taskKinds,
            CommandKind.planKinds,
            CommandKind.createKinds,
            CommandKind.occurrenceKinds,
            CommandKind.settingsKinds,
            CommandKind.moveKinds,
            CommandKind.definitionKinds,
        )
        assertEquals(CommandKind.entries.toSet(), partitions.flatten().toSet())
        for (i in partitions.indices) {
            for (j in i + 1 until partitions.size) {
                assertTrue(partitions[i].none { it in partitions[j] }, "the filters must be disjoint")
            }
        }
        assertTrue(CommandKind.planKinds.all { it.category == CommandCategory.Plan })
        assertTrue(CommandKind.createKinds.all { it.category == CommandCategory.Create })
        assertTrue(CommandKind.occurrenceKinds.all { it.category == CommandCategory.Occurrence })
        assertTrue(CommandKind.settingsKinds.all { it.category == CommandCategory.Settings })
        assertTrue(CommandKind.moveKinds.all { it.category == CommandCategory.Move })
        assertEquals(listOf(CommandKind.MoveItem), CommandKind.moveKinds, "the move catalog is the single cross-kind move verb")
        assertTrue(CommandKind.definitionKinds.all { it.category == CommandCategory.Definition })
        assertEquals(
            listOf(CommandKind.SetDefinitionState),
            CommandKind.definitionKinds,
            "the definition catalog is the single recurring-definition light-switch verb",
        )
        assertTrue(
            CommandKind.taskKinds.none {
                it.category == CommandCategory.Plan ||
                    it.category == CommandCategory.Create ||
                    it.category == CommandCategory.Occurrence ||
                    it.category == CommandCategory.Settings ||
                    it.category == CommandCategory.Move ||
                    it.category == CommandCategory.Definition
            },
        )
    }
}

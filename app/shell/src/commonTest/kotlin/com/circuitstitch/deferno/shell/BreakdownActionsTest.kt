package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BreakdownActionsTest {

    @Test
    fun captureSubtask_creates_a_Task_child_under_the_parent_and_returns_its_id() = runTest {
        var captured: CreateItem.Payload? = null
        val actions = CommandBreakdownActions(
            createItem = { captured = it; CommandResult.Accepted(CommandKind.CreateItem, itemId = "child-1") },
            workingStateEditor = WorkingStateEditor { _, _, _ -> },
            addToPlanFn = { _ -> },
        )

        val id = actions.captureSubtask(parentId = "parent-9", title = "  draft the outline  ")

        assertEquals("child-1", id)
        val payload = assertIs<CreateItem.Payload.Task>(captured)
        assertEquals("parent-9", payload.payload.parentId)
        assertEquals("draft the outline", payload.payload.title) // trimmed
    }

    @Test
    fun captureSubtask_skips_a_blank_title() = runTest {
        var created = false
        val actions = CommandBreakdownActions(
            createItem = { created = true; CommandResult.Accepted(CommandKind.CreateItem) },
            workingStateEditor = WorkingStateEditor { _, _, _ -> },
            addToPlanFn = { _ -> },
        )

        assertNull(actions.captureSubtask(parentId = "parent-9", title = "   "))
        assertEquals(false, created)
    }

    @Test
    fun drop_sets_the_working_state_to_Dropped() = runTest {
        var target: Pair<TaskId, WorkingState>? = null
        val actions = CommandBreakdownActions(
            createItem = { CommandResult.Accepted(CommandKind.CreateItem) },
            workingStateEditor = WorkingStateEditor { id, state, _ -> target = id to state },
            addToPlanFn = { _ -> },
        )

        actions.drop("task-7")

        assertEquals(TaskId("task-7") to WorkingState.Dropped, target)
    }

    @Test
    fun addToPlan_dispatches_the_task_to_todays_plan() = runTest {
        var planned: TaskId? = null
        val actions = CommandBreakdownActions(
            createItem = { CommandResult.Accepted(CommandKind.CreateItem) },
            workingStateEditor = WorkingStateEditor { _, _, _ -> },
            addToPlanFn = { id -> planned = id },
        )

        actions.addToPlan("task-7")

        assertEquals(TaskId("task-7"), planned)
    }
}

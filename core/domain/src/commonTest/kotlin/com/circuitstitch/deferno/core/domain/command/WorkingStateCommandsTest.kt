package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The single [WorkingState] → [TaskCommand] mapping the interactive Tasks detail relies on (#73,
 * ADR-0007): each of the five working states resolves to exactly the one status verb that reaches it,
 * so the transition matrix lives once in `core:domain` and the UI never re-derives it. A literal,
 * hand-written expectation table — not re-derived from the SUT — so an inverted mapping fails red.
 */
class WorkingStateCommandsTest {

    private val id = TaskId("t1")

    @Test
    fun everyWorkingStateMapsToItsOneLifecycleVerb() {
        assertEquals(ReopenTask(id), taskCommandFor(id, WorkingState.Open))
        assertEquals(StartTask(id), taskCommandFor(id, WorkingState.InProgress))
        assertEquals(SendTaskToReview(id), taskCommandFor(id, WorkingState.InReview))
        assertEquals(CompleteTask(id), taskCommandFor(id, WorkingState.Done))
        assertEquals(DropTask(id), taskCommandFor(id, WorkingState.Dropped))
    }

    @Test
    fun everyWorkingStateMapsToAStatusVerbAndIsTotal() {
        // Total over the enum: each state yields a Status-category command (no state falls through).
        for (state in WorkingState.entries) {
            assertEquals(CommandCategory.Status, taskCommandFor(id, state).kind.category, "$state")
        }
    }
}

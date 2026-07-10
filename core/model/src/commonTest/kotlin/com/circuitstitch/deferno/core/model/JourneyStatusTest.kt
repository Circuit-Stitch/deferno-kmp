package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Contract for the read-only **journey status** reading (ADR-0044): the display-only vocabulary derived
 * from a [WorkingState] plus the orthogonal, server-derived [Task.blocked] flag. This is the reading the
 * indicator renders — the table below is the ratified mapping (ADR-0044 §1a). `blocked` overrides ONLY the
 * non-terminal states and forces the Middle slot; terminal (Done) and shelved (Dropped) ignore it.
 */
class JourneyStatusTest {

    @Test
    fun open_notBlocked_isInitialToDo() {
        assertEquals(
            JourneyStatus(JourneySlot.Initial, JourneyLabel.ToDo, JourneyStyle.Normal),
            journeyStatus(WorkingState.Open, blocked = false),
        )
    }

    @Test
    fun inProgress_notBlocked_isMiddleInProgress() {
        assertEquals(
            JourneyStatus(JourneySlot.Middle, JourneyLabel.InProgress, JourneyStyle.Normal),
            journeyStatus(WorkingState.InProgress, blocked = false),
        )
    }

    @Test
    fun inReview_notBlocked_isMiddleInReview() {
        assertEquals(
            JourneyStatus(JourneySlot.Middle, JourneyLabel.InReview, JourneyStyle.Normal),
            journeyStatus(WorkingState.InReview, blocked = false),
        )
    }

    @Test
    fun done_isTerminalDone() {
        assertEquals(
            JourneyStatus(JourneySlot.Terminal, JourneyLabel.Done, JourneyStyle.Normal),
            journeyStatus(WorkingState.Done, blocked = false),
        )
    }

    @Test
    fun dropped_isMiddleNotDoing() {
        assertEquals(
            JourneyStatus(JourneySlot.Middle, JourneyLabel.NotDoing, JourneyStyle.NotDoing),
            journeyStatus(WorkingState.Dropped, blocked = false),
        )
    }

    @Test
    fun blocked_overridesEveryNonTerminalState_toBlockedMiddle() {
        // The BLOCKED reading is a Middle marker in the error style — "started-but-stuck" — for Open,
        // InProgress, and InReview alike (blocked is orthogonal to the lifecycle, ADR-0044).
        val blockedMiddle = JourneyStatus(JourneySlot.Middle, JourneyLabel.Blocked, JourneyStyle.Blocked)
        assertEquals(blockedMiddle, journeyStatus(WorkingState.Open, blocked = true))
        assertEquals(blockedMiddle, journeyStatus(WorkingState.InProgress, blocked = true))
        assertEquals(blockedMiddle, journeyStatus(WorkingState.InReview, blocked = true))
    }

    @Test
    fun blocked_isIgnoredOnDone_terminalWins() {
        // A finished item is never rendered BLOCKED — terminal-wins (ADR-0044 §8.3).
        assertEquals(
            JourneyStatus(JourneySlot.Terminal, JourneyLabel.Done, JourneyStyle.Normal),
            journeyStatus(WorkingState.Done, blocked = true),
        )
    }

    @Test
    fun blocked_isIgnoredOnDropped_shelvedWins() {
        // A shelved item is never rendered BLOCKED — it stays NOT DOING (ADR-0044 §1a).
        assertEquals(
            JourneyStatus(JourneySlot.Middle, JourneyLabel.NotDoing, JourneyStyle.NotDoing),
            journeyStatus(WorkingState.Dropped, blocked = true),
        )
    }

    @Test
    fun taskExtension_readsOverWorkingStateAndBlocked() {
        // The Task.journeyStatus() extension is journeyStatus(workingState, blocked).
        assertEquals(
            JourneyStatus(JourneySlot.Middle, JourneyLabel.Blocked, JourneyStyle.Blocked),
            task(WorkingState.InProgress, blocked = true).journeyStatus(),
        )
        assertEquals(
            JourneyStatus(JourneySlot.Initial, JourneyLabel.ToDo, JourneyStyle.Normal),
            task(WorkingState.Open, blocked = false).journeyStatus(),
        )
    }

    private fun task(state: WorkingState, blocked: Boolean): Task = Task(
        id = TaskId("t"),
        orgSlug = "u-deferno",
        title = "T",
        workingState = state,
        blocked = blocked,
        dateCreated = Instant.parse("2026-06-01T09:00:00Z"),
    )
}

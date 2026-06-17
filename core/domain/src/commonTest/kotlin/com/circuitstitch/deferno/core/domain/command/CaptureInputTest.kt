package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The ADR-0036 behavioral-capture derivation: a jargon-free [CaptureInput] → the kind-specific
 * [CreateItem.Payload]. These pin the cross-repo shared contract (`defernowork-mcp`'s `capture_item`)
 * the OS-intent edge binds to — the Q1/Q2/Q3 tree, the operand mapping, and the trust-boundary
 * validation. The expected kinds are a HAND-WRITTEN truth table (like [CommandKindTest]), deliberately
 * not re-derived from the SUT's own `when`, so an inverted discriminator mirrored into both files
 * cannot pass green.
 */
class CaptureInputTest {

    private val weekly = RecurrenceDto(type = "weekly", days = listOf("MO"))

    private fun capture(
        occursAtSetTime: Boolean,
        repeats: Boolean,
        ifMissed: IfMissed? = null,
    ) = CaptureInput(
        title = "thing",
        occursAtSetTime = occursAtSetTime,
        repeats = repeats,
        ifMissed = ifMissed,
        // Supply every operand a branch might require so the table exercises the kind choice, not validation.
        date = "2026-06-17",
        recurrence = weekly,
    )

    @Test
    fun theDecisionTreeMapsEveryDiscriminatorComboToItsKind() {
        // Columns: (occursAtSetTime, repeats, ifMissed) -> expected ItemKind. A literal spec table over
        // the whole discriminator space — occursAtSetTime wins over repeats (a recurring set-time thing
        // is still an Event), a non-repeating thing is a Task regardless of ifMissed, and a repeating
        // thing splits Chore (carries forward) vs Habit (lapses) on ifMissed.
        val expected = mapOf(
            // occursAtSetTime = true → Event, regardless of repeats / ifMissed (the "recurring + set-time" edge).
            Triple(true, false, null) to ItemKind.Event,
            Triple(true, true, null) to ItemKind.Event,
            Triple(true, true, IfMissed.CarriesForward) to ItemKind.Event,
            Triple(true, true, IfMissed.Lapses) to ItemKind.Event,
            Triple(true, false, IfMissed.Lapses) to ItemKind.Event,
            // occursAtSetTime = false, repeats = false → Task, regardless of ifMissed (the "one-off must-do" edge).
            Triple(false, false, null) to ItemKind.Task,
            Triple(false, false, IfMissed.CarriesForward) to ItemKind.Task,
            Triple(false, false, IfMissed.Lapses) to ItemKind.Task,
            // occursAtSetTime = false, repeats = true → Chore / Habit on ifMissed.
            Triple(false, true, IfMissed.CarriesForward) to ItemKind.Chore,
            Triple(false, true, IfMissed.Lapses) to ItemKind.Habit,
        )

        for ((combo, kind) in expected) {
            val (set, repeats, ifMissed) = combo
            assertEquals(
                kind,
                capture(set, repeats, ifMissed).deriveCreatePayload().itemKind,
                "($set, $repeats, $ifMissed)",
            )
        }
    }

    @Test
    fun aTaskCarriesTitleDeadlineAndDescription_andNoRecurrence() {
        val payload = CaptureInput(
            title = "buy milk",
            occursAtSetTime = false,
            repeats = false,
            description = "2%",
            date = "2026-06-20",
            timeOfDay = "17:00",
        ).deriveCreatePayload()

        val task = (payload as CreateItem.Payload.Task).payload
        assertEquals("buy milk", task.title)
        assertEquals("2%", task.description)
        assertEquals("2026-06-20", task.completeBy)
        assertEquals("17:00", task.deadlineTimeOfDay)
    }

    @Test
    fun anEventCarriesItsSetDayAsCompleteBy_andItsOptionalRecurrence() {
        val payload = CaptureInput(
            title = "standup",
            occursAtSetTime = true,
            repeats = true,
            date = "2026-06-17",
            timeOfDay = "09:00",
            recurrence = weekly,
        ).deriveCreatePayload()

        val event = (payload as CreateItem.Payload.Event).payload
        assertEquals("standup", event.title)
        assertEquals("2026-06-17", event.completeBy)
        assertEquals("09:00", event.startTimeOfDay)
        assertEquals(weekly, event.recurrence)
    }

    @Test
    fun aOneOffEventOmitsRecurrence() {
        val payload = CaptureInput(
            title = "dentist",
            occursAtSetTime = true,
            repeats = false,
            date = "2026-07-01",
        ).deriveCreatePayload()

        assertNull((payload as CreateItem.Payload.Event).payload.recurrence)
    }

    @Test
    fun aChoreAndAHabitCarryTheRecurrence() {
        val chore = CaptureInput("trash", occursAtSetTime = false, repeats = true, ifMissed = IfMissed.CarriesForward, recurrence = weekly)
            .deriveCreatePayload()
        assertEquals(weekly, (chore as CreateItem.Payload.Chore).payload.recurrence)

        val habit = CaptureInput("floss", occursAtSetTime = false, repeats = true, ifMissed = IfMissed.Lapses, recurrence = weekly)
            .deriveCreatePayload()
        assertEquals(weekly, (habit as CreateItem.Payload.Habit).payload.recurrence)
    }

    // --- trust-boundary validation: malformed caller input throws, never a silently-wrong kind ---

    @Test
    fun aBlankTitleIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CaptureInput("   ", occursAtSetTime = false, repeats = false).deriveCreatePayload()
        }
    }

    @Test
    fun anEventWithoutADateIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CaptureInput("standup", occursAtSetTime = true, repeats = false, date = null).deriveCreatePayload()
        }
    }

    @Test
    fun aRepeatingCaptureWithoutARecurrenceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            CaptureInput("trash", occursAtSetTime = false, repeats = true, ifMissed = IfMissed.CarriesForward, recurrence = null)
                .deriveCreatePayload()
        }
    }

    @Test
    fun aRepeatingCaptureWithoutIfMissedIsRejected() {
        // Reached Q3 (repeats, not a set-time Event) but the caller did not answer carries-forward vs
        // lapses — there is no safe Chore/Habit default, so it is a contract error, not a guess.
        assertFailsWith<IllegalArgumentException> {
            CaptureInput("trash", occursAtSetTime = false, repeats = true, ifMissed = null, recurrence = weekly)
                .deriveCreatePayload()
        }
    }
}

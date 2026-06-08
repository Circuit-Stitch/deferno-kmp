package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * Unit coverage for the **New** create surface's payload building (#71, ADR-0016) — the pure,
 * Compose-free [NewState.toPayload] mapping + its [NewState.canSubmit] gate. The Event arm is the
 * regression target (FIX 1, AC #2): a bare Event create must carry a **non-empty start** (`complete_by`)
 * and must **not** carry empty-string optional fields (the server rejects `"complete_by":""`, and
 * `explicitNulls=false` omits nulls but NOT empty strings). The companion serialization assertion — that
 * the *wire body* drops the empty optionals — lives in `core:network`'s `CreatePayloadSerializationTest`
 * (where `DefernoJson` is first-class); here we assert the payload the mapping actually emits.
 */
class NewComponentTest {

    private val start = Instant.parse("2026-06-08T09:00:00Z")
    private val end = Instant.parse("2026-06-08T10:00:00Z")

    @Test
    fun eventPayloadCarriesANonEmptyStart() {
        val payload = NewState(selectedKind = ItemKind.Event, title = "standup", start = start).toPayload()

        assertTrue("Event maps to an Event payload", payload is CreateItem.Payload.Event)
        val event = (payload as CreateItem.Payload.Event).payload
        assertTrue("the Event start (complete_by) is non-empty", event.completeBy.isNotBlank())
        assertEquals(start.toString(), event.completeBy)
    }

    @Test
    fun bareEventCreateHasNoEmptyStringOptionalFields() {
        // No notes, no end: the optional fields must be NULL (omitted by explicitNulls=false), never "".
        val event = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start)
            .toPayload() as CreateItem.Payload.Event).payload

        assertNull("blank notes must be null, not \"\"", event.description)
        assertNull("an absent end must be null, not \"\"", event.endTime)
        assertFalse("the start is never an empty string", event.completeBy.isEmpty())
    }

    @Test
    fun eventPayloadCarriesTheEndWhenSupplied_andOmitsItWhenNot() {
        val withEnd = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start, end = end)
            .toPayload() as CreateItem.Payload.Event).payload
        assertEquals(end.toString(), withEnd.endTime)

        val noEnd = (NewState(selectedKind = ItemKind.Event, title = "standup", start = start)
            .toPayload() as CreateItem.Payload.Event).payload
        assertNull("no end when not supplied", noEnd.endTime)
    }

    @Test
    fun anEventWithoutAStartCannotBeSubmitted() {
        // An Event needs a fixed start (AC #2). Title alone is not enough for the Event kind.
        assertFalse(
            "an Event with no start is not submittable",
            NewState(selectedKind = ItemKind.Event, title = "standup", start = null).canSubmit,
        )
        assertTrue(
            "an Event with a start is submittable",
            NewState(selectedKind = ItemKind.Event, title = "standup", start = start).canSubmit,
        )
    }

    @Test
    fun nonEventKindsStillSubmitWithJustATitle() {
        // The start requirement is Event-specific; the other kinds only need a non-blank title.
        assertTrue(NewState(selectedKind = ItemKind.Task, title = "buy milk").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Habit, title = "stretch").canSubmit)
        assertTrue(NewState(selectedKind = ItemKind.Chore, title = "trash").canSubmit)

        val task = (NewState(selectedKind = ItemKind.Task, title = "buy milk").toPayload()
            as CreateItem.Payload.Task).payload
        assertEquals("buy milk", task.title)
        assertNull("blank notes never become \"\" on a Task either", task.description)
    }
}

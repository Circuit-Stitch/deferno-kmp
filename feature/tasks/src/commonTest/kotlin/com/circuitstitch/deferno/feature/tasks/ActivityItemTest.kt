package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** [mergeActivity] (ADR-0046): interleave comments + history into one REVERSE-chronological Trail with stable ids. */
class ActivityItemTest {

    private fun comment(id: String, at: String) =
        Comment(id = id, taskId = TaskId("t"), body = "b", createdBy = UserId("u"), createdAt = Instant.parse(at))

    @Test
    fun mergesAndSortsCommentsAndHistoryNewestFirst() {
        val comments = listOf(comment("c1", "2026-06-21T12:00:00Z"), comment("c2", "2026-06-21T12:10:00Z"))
        val history = listOf(
            ItemHistoryEvent.Created(Instant.parse("2026-06-21T11:00:00Z")),
            ItemHistoryEvent.Updated(Instant.parse("2026-06-21T12:05:00Z"), listOf("title")),
        )

        val feed = mergeActivity(comments, history)

        // Reverse-chronological across both sources (ADR-0046): newest first.
        assertEquals(
            listOf("c2", "history:1", "c1", "history:0"),
            feed.map { it.id },
        )
    }

    @Test
    fun historyEventsGetStableIndexBasedIds() {
        val history = listOf(
            ItemHistoryEvent.Created(Instant.parse("2026-06-21T11:00:00Z")),
            ItemHistoryEvent.MergedIntoParent(Instant.parse("2026-06-21T11:30:00Z")),
        )

        val feed = mergeActivity(emptyList(), history)

        // Ids stay index-based (position in the append-only history), but the feed is newest-first.
        assertEquals(listOf("history:1", "history:0"), feed.map { it.id })
        val newest = feed.first() as ActivityItem.HistoryEvent
        assertEquals(ItemHistoryEvent.MergedIntoParent(Instant.parse("2026-06-21T11:30:00Z")), newest.event)
    }

    @Test
    fun aCommentRowExposesItsCommentAndId() {
        val feed = mergeActivity(listOf(comment("c9", "2026-06-21T12:00:00Z")), emptyList())

        val row = feed.single() as ActivityItem.Comment
        assertEquals("c9", row.id)
        assertEquals("c9", row.comment.id)
    }

    // --- Local ledger old->new diff carry-over (#260) ---

    private fun titleEdit(at: String, old: String, new: String) = LedgerEdit(
        Instant.parse(at),
        listOf(ActivityFieldChange(ActivityField.Title, "title", present(old), present(new))),
    )

    private fun present(value: String): ActivityFieldValue = ActivityFieldValue.Present(value)

    @Test
    fun graftsALocalEditsValuesOntoAMatchingUpdatedRow() {
        val history = listOf(ItemHistoryEvent.Updated(Instant.parse("2026-06-21T12:05:00Z"), listOf("title")))
        val edits = listOf(titleEdit("2026-06-21T12:05:02Z", "Old", "New"))

        val row = mergeActivity(emptyList(), history, edits).single() as ActivityItem.HistoryEvent

        val change = row.changes.single()
        assertEquals(ActivityField.Title, change.field)
        assertEquals(present("Old"), change.before)
        assertEquals(present("New"), change.after)
    }

    @Test
    fun leavesAnUpdatedRowUnenrichedWhenNoLocalEditMatches() {
        val history = listOf(ItemHistoryEvent.Updated(Instant.parse("2026-06-21T12:05:00Z"), listOf("title")))
        // A status edit doesn't overlap the event's title field, so it never grafts on.
        val nonMatching = listOf(
            LedgerEdit(
                Instant.parse("2026-06-21T12:05:00Z"),
                listOf(ActivityFieldChange(ActivityField.Status, "status", present("open"), present("done"))),
            ),
        )

        val fromEmpty = mergeActivity(emptyList(), history, emptyList()).single() as ActivityItem.HistoryEvent
        val fromNonMatch = mergeActivity(emptyList(), history, nonMatching).single() as ActivityItem.HistoryEvent

        assertEquals(emptyList(), fromEmpty.changes)
        assertEquals(emptyList(), fromNonMatch.changes)
    }

    @Test
    fun consumesEachLocalEditAtMostOnceAcrossRepeatedFieldEdits() {
        val history = listOf(
            ItemHistoryEvent.Updated(Instant.parse("2026-06-21T12:00:00Z"), listOf("title")),
            ItemHistoryEvent.Updated(Instant.parse("2026-06-21T13:00:00Z"), listOf("title")),
        )
        val edits = listOf(titleEdit("2026-06-21T12:00:01Z", "A", "B"), titleEdit("2026-06-21T13:00:01Z", "B", "C"))

        val feed = mergeActivity(emptyList(), history, edits).filterIsInstance<ActivityItem.HistoryEvent>()

        // Each Updated row is enriched by its own nearest edit — never the same edit twice.
        assertEquals(present("B"), feed.first { it.id == "history:0" }.changes.single().after)
        assertEquals(present("C"), feed.first { it.id == "history:1" }.changes.single().after)
    }
}

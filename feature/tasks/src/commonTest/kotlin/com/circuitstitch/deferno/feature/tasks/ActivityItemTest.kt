package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** [mergeActivity] (ADR-0043): interleave comments + history into one chronological feed with stable ids. */
class ActivityItemTest {

    private fun comment(id: String, at: String) =
        Comment(id = id, taskId = TaskId("t"), body = "b", createdBy = UserId("u"), createdAt = Instant.parse(at))

    @Test
    fun mergesAndSortsCommentsAndHistoryByInstant() {
        val comments = listOf(comment("c1", "2026-06-21T12:00:00Z"), comment("c2", "2026-06-21T12:10:00Z"))
        val history = listOf(
            ItemHistoryEvent.Created(Instant.parse("2026-06-21T11:00:00Z")),
            ItemHistoryEvent.Updated(Instant.parse("2026-06-21T12:05:00Z"), listOf("title")),
        )

        val feed = mergeActivity(comments, history)

        // Chronological across both sources.
        assertEquals(
            listOf("history:0", "c1", "history:1", "c2"),
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

        assertEquals(listOf("history:0", "history:1"), feed.map { it.id })
        val first = feed.first() as ActivityItem.HistoryEvent
        assertEquals(ItemHistoryEvent.Created(Instant.parse("2026-06-21T11:00:00Z")), first.event)
    }

    @Test
    fun aCommentRowExposesItsCommentAndId() {
        val feed = mergeActivity(listOf(comment("c9", "2026-06-21T12:00:00Z")), emptyList())

        val row = feed.single() as ActivityItem.Comment
        assertEquals("c9", row.id)
        assertEquals("c9", row.comment.id)
    }
}

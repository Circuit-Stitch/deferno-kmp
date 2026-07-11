package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-time old->new field diff ([ActivityEntry.changes], #260 follow-up): zips the captured
 * new-value `body` and old-value `before` JSON per key into typed [ActivityField] changes, handling
 * cleared (wire null / emptied list), unavailable (absent key), and unknown keys — and never crashing
 * on a malformed payload.
 */
class ActivityDiffTest {

    private fun entry(body: String?, before: String?) = ActivityEntry(
        seq = 1,
        recordedAt = Instant.parse("2026-06-21T12:00:00Z"),
        source = ActivitySource.Mobile,
        target = "task:a",
        method = OutboxMethod.Patch,
        path = listOf("tasks", "a"),
        body = body,
        before = before,
    )

    @Test
    fun noPayloadYieldsNoDiff() {
        assertTrue(entry(null, null).changes().isEmpty())
    }

    @Test
    fun zipsOldAndNewPerKey() {
        val change = entry("""{"title":"New"}""", """{"title":"Old"}""").changes().single()
        assertEquals(ActivityField.Title, change.field)
        assertEquals("title", change.rawKey)
        assertEquals(ActivityFieldValue.Present("Old"), change.before)
        assertEquals(ActivityFieldValue.Present("New"), change.after)
    }

    @Test
    fun wireNullReadsAsCleared() {
        val change = entry("""{"complete_by":null}""", """{"complete_by":"2026-07-15T00:00:00Z"}""").changes().single()
        assertEquals(ActivityField.Deadline, change.field)
        assertEquals(ActivityFieldValue.Present("2026-07-15T00:00:00Z"), change.before)
        assertEquals(ActivityFieldValue.Cleared, change.after)
    }

    @Test
    fun absentBeforeKeyReadsAsUnavailable() {
        // e.g. an un-hydrated description edit — the before-image omits the key entirely.
        val change = entry("""{"description":"body"}""", "{}").changes().single()
        assertEquals(ActivityField.Description, change.field)
        assertEquals(ActivityFieldValue.Unavailable, change.before)
        assertEquals(ActivityFieldValue.Present("body"), change.after)
    }

    @Test
    fun labelsJoinAndAnEmptyListClears() {
        val change = entry("""{"labels":["a","b"]}""", """{"labels":[]}""").changes().single()
        assertEquals(ActivityField.Labels, change.field)
        assertEquals(ActivityFieldValue.Cleared, change.before)
        assertEquals(ActivityFieldValue.Present("a, b"), change.after)
    }

    @Test
    fun pinnedAndStatusRenderRawTokens() {
        val pinned = entry("""{"pinned":true}""", """{"pinned":false}""").changes().single()
        assertEquals(ActivityField.Pinned, pinned.field)
        assertEquals(ActivityFieldValue.Present("false"), pinned.before)
        assertEquals(ActivityFieldValue.Present("true"), pinned.after)

        val status = entry("""{"status":"done"}""", """{"status":"in-progress"}""").changes().single()
        assertEquals(ActivityField.Status, status.field)
        assertEquals(ActivityFieldValue.Present("in-progress"), status.before)
        assertEquals(ActivityFieldValue.Present("done"), status.after)
    }

    @Test
    fun unknownKeyKeepsRawKeyForFallback() {
        val change = entry("""{"theme_family":"mono"}""", null).changes().single()
        assertEquals(ActivityField.Unknown, change.field)
        assertEquals("theme_family", change.rawKey)
        assertEquals(ActivityFieldValue.Unavailable, change.before)
        assertEquals(ActivityFieldValue.Present("mono"), change.after)
    }

    @Test
    fun malformedJsonSwallowsToAnEmptyDiff() {
        assertTrue(entry("{not json", null).changes().isEmpty())
    }

    @Test
    fun multipleFieldsPreserveBodyOrder() {
        val fields = entry("""{"title":"T","description":"D"}""", """{"title":"t","description":"d"}""")
            .changes().map { it.field }
        assertEquals(listOf(ActivityField.Title, ActivityField.Description), fields)
    }
}

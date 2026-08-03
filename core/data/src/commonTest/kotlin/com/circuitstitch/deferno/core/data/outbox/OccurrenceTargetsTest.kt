package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `occurrence:<Kind>:<definitionId>:<date>` target scheme (#380) — the sibling of
 * `CommentTargetsTest`. It pins the exact encoding (three writers and every reader key on the literal
 * string), the [OccurrenceTargets.of] → [OccurrenceTargets.parse] round trip, and — the point of the
 * helper — that `parse` is **total**: every malformed or foreign target degrades to `null` rather than
 * throwing, because the callers scan a mixed outbox they do not otherwise understand (ADR-0005).
 */
class OccurrenceTargetsTest {

    private val date = LocalDate(2026, 6, 8)

    @Test
    fun ofEncodesTheKindEnumNameTheDefinitionIdAndTheIsoDay() {
        assertEquals("occurrence:Habit:hab-3-item:2026-06-08", OccurrenceTargets.of(ItemKind.Habit, "hab-3-item", date))
        assertEquals("occurrence:Chore:cho-1-item:2026-06-08", OccurrenceTargets.of(ItemKind.Chore, "cho-1-item", date))
        assertEquals("occurrence:Event:evt-1-item:2026-06-08", OccurrenceTargets.of(ItemKind.Event, "evt-1-item", date))
    }

    @Test
    fun everyRecurringKindRoundTrips() {
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            val target = OccurrenceTargets.of(kind, "1f3c0f6e-0000-4000-8000-000000000001", date)
            assertEquals(
                OccurrenceTarget(kind, "1f3c0f6e-0000-4000-8000-000000000001", date),
                OccurrenceTargets.parse(target),
                "round trip for $kind",
            )
        }
    }

    @Test
    fun aForeignTargetIsNotAnOccurrence() {
        // The outbox is mixed: a scanner must skip everything it does not own without throwing.
        assertNull(OccurrenceTargets.parse("task:t-1"))
        assertNull(OccurrenceTargets.parse("create:habit:client-1"))
        assertNull(OccurrenceTargets.parse("comment:t-1:c-1"))
        assertNull(OccurrenceTargets.parse("plan:2026-06-08"))
        assertNull(OccurrenceTargets.parse("settings"))
        assertNull(OccurrenceTargets.parse(""))
    }

    @Test
    fun anUnknownKindTokenDegradesToNull() {
        // A future kind (or the wire's lowercase token, which this scheme deliberately does not use)
        // must not resolve — a mis-keyed row is skipped, never routed to the wrong endpoint.
        assertNull(OccurrenceTargets.parse("occurrence:Sprint:s-1:2026-06-08"))
        assertNull(OccurrenceTargets.parse("occurrence:habit:s-1:2026-06-08"))
        // Task has no occurrence endpoints, but it IS an ItemKind name — parsing it is honest; the
        // intent builders are what reject it. Pinned so the boundary is deliberate, not accidental.
        assertEquals(ItemKind.Task, OccurrenceTargets.parse("occurrence:Task:t-1:2026-06-08")?.kind)
    }

    @Test
    fun anUnparseableDateDegradesToNull() {
        assertNull(OccurrenceTargets.parse("occurrence:Habit:h-1:not-a-date"))
        assertNull(OccurrenceTargets.parse("occurrence:Habit:h-1:2026-13-45"))
        assertNull(OccurrenceTargets.parse("occurrence:Habit:h-1:"))
    }

    @Test
    fun aBlankDefinitionIdDegradesToNull() {
        assertNull(OccurrenceTargets.parse("occurrence:Habit::2026-06-08"))
    }

    @Test
    fun aWrongSegmentCountDegradesToNull() {
        assertNull(OccurrenceTargets.parse("occurrence:Habit:h-1"))
        assertNull(OccurrenceTargets.parse("occurrence:Habit"))
        assertNull(OccurrenceTargets.parse("occurrence:Habit:h-1:2026-06-08:extra"))
    }

    @Test
    fun thePrefixIsTheOneTheWritersUse() {
        assertEquals("occurrence:", OccurrenceTargets.PREFIX)
        assertEquals(true, OccurrenceTargets.of(ItemKind.Event, "e-1", date).startsWith(OccurrenceTargets.PREFIX))
    }
}

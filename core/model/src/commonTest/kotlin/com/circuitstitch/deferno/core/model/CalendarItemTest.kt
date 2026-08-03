package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two derived predicates on a calendar feed row (#74, #380) — the gates every renderer and the
 * occurrence write path key on. [CalendarItem.isActionableOccurrence] is deliberately a **conjunction
 * of four independent conditions**, each of which has a real failure mode behind it, so each is pinned
 * separately here rather than being implied by one happy-path case.
 */
class CalendarItemTest {

    private val day = LocalDate(2026, 6, 8)

    private fun row(
        seriesId: String? = "hab-3-series",
        kind: ItemKind? = ItemKind.Habit,
        source: CalendarSource = CalendarSource.Deferno,
    ) = CalendarItem(
        id = "ce-1",
        taskId = "hab-3-item",
        seriesId = seriesId,
        title = "Morning stretch",
        date = day,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = WorkingState.Open,
        kind = kind,
        source = source,
    )

    @Test
    fun aDefernoFiringWithAResolvedRecurringKindIsActionable() {
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(row(kind = kind).isActionableOccurrence, "$kind firing must be actionable")
        }
    }

    @Test
    fun aRowWithNoSeriesIsNotAFiring() {
        // A one-off dated Task: rendered in the agenda, acted on through the Task path instead.
        val oneOff = row(seriesId = null, kind = ItemKind.Task)
        assertFalse(oneOff.isActionableOccurrence)
        assertTrue(oneOff.isDatedTask)
    }

    @Test
    fun anUnresolvedKindRendersReadOnly() {
        // The gentle degradation: a firing whose kind we could not resolve offers no verb rather than
        // guessing an endpoint. Was the *permanent* state of every recurring row before #380.
        assertFalse(row(kind = null).isActionableOccurrence)
    }

    @Test
    fun aTaskKindedFiringIsNotActionable() {
        // Task has no occurrence endpoints at all; the intent builders `error()` on it.
        assertFalse(row(kind = ItemKind.Task).isActionableOccurrence)
    }

    @Test
    fun anExternallySourcedRowIsNeverActionable() {
        // The backend stores a synced Google event as an Event-KIND item and tells clients to gate on
        // `source`. Belt-and-braces today (external rows carry no series id) — load-bearing the day the
        // provider's recurrence is expanded into firings.
        assertFalse(row(source = CalendarSource.External).isActionableOccurrence)
        assertFalse(row(source = CalendarSource.Unknown).isActionableOccurrence)
    }

    @Test
    fun isDatedTaskIsDefernoOwnedAndSeriesLess() {
        assertTrue(row(seriesId = null).isDatedTask)
        // A firing is not a dated task…
        assertFalse(row().isDatedTask)
        // …and neither is a series-less external event.
        assertFalse(row(seriesId = null, source = CalendarSource.External).isDatedTask)
    }

    @Test
    fun theActPathAddressesTheItemIdNotTheSeriesId() {
        // #380 in one assertion: these are two different values, and `taskId` is the non-null one the
        // occurrence endpoints resolve through `load_item_for_user`.
        val firing = row()
        assertEquals("hab-3-item", firing.taskId)
        assertEquals("hab-3-series", firing.seriesId)
    }
}

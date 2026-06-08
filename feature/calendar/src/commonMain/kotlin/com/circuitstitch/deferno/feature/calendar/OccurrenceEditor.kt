package com.circuitstitch.deferno.feature.calendar

import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.datetime.LocalDate

/**
 * The occurrence-act seam the Calendar drives (#74) — the firing-level sibling of the Tasks slice's
 * `WorkingStateEditor`. The component calls it to mark / clear / reschedule a firing; the shell backs
 * it with the command executor (optimistic local apply + outbox enqueue through the command registry,
 * ADR-0007/0001), so the feature layer never touches the registry directly. It suspends so the host
 * runs each act on the shell scope.
 */
interface OccurrenceEditor {
    /** Mark the firing [itemId] with a coarse [action] (start / complete / skip). */
    suspend fun mark(itemId: String, action: OccurrenceAction)

    /** Clear the firing [itemId] back to Scheduled — the forgiving undo. */
    suspend fun clear(itemId: String)

    /** Reschedule the firing [itemId] to [newDate] (Events only in v1). */
    suspend fun reschedule(itemId: String, newDate: LocalDate)
}

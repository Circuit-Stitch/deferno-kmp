package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate

/**
 * One dated firing of a recurring definition (CONTEXT.md → "Occurrence") — a Habit/Chore/Event
 * occurrence. Kept **strictly distinct** from its parent definition: a definition carries a
 * [DefinitionState] light switch, an Occurrence carries an [OccurrenceState] (how *this* firing went).
 *
 * The Occurrence points back at its definition through [definitionId] (the parent's UUID, as a raw
 * String since a definition can be any of the three kinds) and [kind] (which kind that definition is),
 * and is dated by [date] (the calendar day it fires). This is a read projection: the client *writes*
 * coarse [OccurrenceAction]s against it, while [state] (including the server-derived `scheduled`/
 * `missed`/punctuality) is read-only (ADR-0011 read/write asymmetry).
 */
data class Occurrence(
    val id: OccurrenceId,
    val definitionId: String,
    val kind: ItemKind,
    val date: LocalDate,
    val state: OccurrenceState,
)

package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The projection of the unified wire `Occurrence` carried **inline on a plan row** — the declared type
 * of `today_occurrence` on all three recurring [ItemView] variants ([ItemView.Habit],
 * [ItemView.Chore], [ItemView.Event]), and nothing else (ADR-0011, #71).
 *
 * **This KDoc used to claim it was the element shape of all three kind-scoped occurrence lists — that
 * was false and it is corrected here (#390).** Those three endpoints return three genuinely different
 * structs, now modelled separately: [HabitOccurrenceDto] (`Envelope_Vec_HabitOccurrence` — three
 * fields, no id and no status), [ChoreOccurrenceDto] (`Envelope_Vec_ChoreOccurrenceView` — a derived
 * view with a `segment_id` and no id at all) and [EventOccurrenceDto] (`Envelope_Vec_Occurrence` —
 * the full sixteen-field struct). Only the last is `Occurrence`-shaped.
 *
 * The required [id]/[parentId] are correct for *this* path and only this path: the plan seeder types
 * `today_occurrence` as the full `Occurrence` struct for every recurring kind
 * (`backend/src/repository/daily_plan.rs:58/:65/:77`), using an all-zeroes id as its "no stored
 * record" placeholder rather than omitting the field (`daily_plan.rs:107`, `:115`). This is a **live
 * decode path** — every `/items/plan` read goes through it — and a missing required key there fails
 * the *whole* response, not one row, so this shape is load-bearing and stays exactly as it is.
 *
 * Lossless + tolerant like the sibling read DTOs: snake_case via [SerialName], the [status] enum
 * defaults to [OccurrenceStatusWire.Unknown] so additive tokens degrade rather than crash, and the
 * tolerant reader ignores the unmodelled override/attachment/comment fields. `parent_id` is the
 * definition the firing belongs to; `scheduled_date` is the calendar day (ISO `yyyy-mm-dd`).
 *
 * Its [status] may be a server *reading* rather than a stored fact, so it is render-only and never
 * reaches a table (ADR-0053 decision 4). The stored half of a firing is `OccurrenceFact`, built from
 * the three per-kind DTOs above by `mapper/OccurrenceFactMapper.kt`.
 */
@Serializable
data class OccurrenceDto(
    val id: String,
    @SerialName("parent_id") val parentId: String,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("complete_by") val completeBy: String? = null,
    val status: OccurrenceStatusWire = OccurrenceStatusWire.Unknown,
)

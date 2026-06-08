package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The faithful flat wire DTO for one **Occurrence** — a dated firing of a recurring definition
 * (ADR-0011, #71). It is the element shape returned by the kind-scoped occurrence reads
 * (`GET /habits/{id}/occurrences`, `GET /chores/{id}/occurrences`, `GET /events/{id}/occurrences` →
 * `Envelope_Vec_Occurrence`). The wire carries **no `kind`** (the parent kind is known from the
 * endpoint that returned it), so the DTO→domain mapper takes the kind alongside (see
 * `mapper/OccurrenceMapper.kt`).
 *
 * Lossless + tolerant like the sibling read DTOs: snake_case via [SerialName], the [status] enum
 * defaults to [OccurrenceStatusWire.Unknown] so additive tokens degrade rather than crash, and the
 * tolerant reader ignores the unmodelled override/attachment/comment fields. `parent_id` is the
 * definition the firing belongs to; `scheduled_date` is the calendar day (ISO `yyyy-mm-dd`).
 */
@Serializable
data class OccurrenceDto(
    val id: String,
    @SerialName("parent_id") val parentId: String,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("complete_by") val completeBy: String? = null,
    val status: OccurrenceStatusWire = OccurrenceStatusWire.Unknown,
)

package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire `series` block — the stored expansion inputs behind a recurring definition (ADR-0053
 * decision 2, `Circuit-Stitch/Deferno#643`), mirroring the backend's `SeriesInputs`
 * (`backend/src/models/recurrence.rs`). Paired with the row's `recurrence` it is everything
 * `expandOccurrenceGrid` needs to reproduce the server's [[Occurrence grid]] with the network gone.
 *
 * **It rides the list read, not just the detail read.** `series` sits beside `series_id` on every
 * `/items` row as well as on `/items/{id}` and `/items/plan` (verified live 2026-08-05,
 * CONTRACT-NOTES → "The `series` block"), which is what lets an Item-tree row expand its own grid
 * cold. That is why it is declared on the three [ItemView] variants and not only on the detail DTOs.
 *
 * **Tolerant to the point of uselessness, on purpose (ADR-0005).** Every field is nullable-or-
 * defaulted and every timestamp stays a raw `String`, parsed defensively one layer up in
 * `mapper/SeriesInputsMapper.kt`. This block is additive and decodes inside the single-call `/items`
 * response, so a strict field here would fail the **whole snapshot** rather than one row — the
 * cold-sync stall of #381, which took the app down. A block that arrives without an anchor, or with
 * an anchor this build cannot parse, must degrade to "no inputs" and leave the row otherwise intact.
 *
 * **Absent is not empty.** The backend elides the whole block when no series row backs the item
 * rather than repairing one on read (`series_repair` re-anchors from the live `complete_by`, which on
 * a recurring definition is a walked *cursor* — a repair would bake the cursor in as the anchor, the
 * exact drift these inputs exist to expose). A `null` DTO therefore means "this device cannot
 * reproduce that grid", never "that grid has no exclusions". The domain keeps the distinction: see
 * `core/model/SeriesInputs.kt`.
 */
@Serializable
data class SeriesInputsDto(
    /**
     * The frozen `DTSTART` as a **local wall time** (no offset, no `Z`), interpreted in [tzid].
     *
     * It is the series' anchor and it **does not move**: on a recurring definition `complete_by` is a
     * cursor that walks forward on every mark-done while this stays put. Nullable only for tolerance
     * — the server always sends it.
     */
    @SerialName("dtstart_local") val dtstartLocal: String? = null,
    /** The IANA zone the series was **frozen** in, e.g. `America/Los_Angeles` — never the reader's. */
    val tzid: String? = null,
    /**
     * The [[Segment]] bound as a UTC instant, **EXCLUSIVE**, set only when a series was split; `null`
     * when open-ended. Not to be confused with the *rule's* `end.on_date`, which is inclusive.
     */
    @SerialName("until_utc") val untilUtc: String? = null,
    /**
     * Local wall times excluded from the expansion (RFC 5545 `EXDATE`). **Always `[]` on the wire
     * today** — no backend handler populates it — but decoded so it is correct for free the day one
     * does.
     */
    val exdates: List<String> = emptyList(),
    /** Per-occurrence exceptions, ascending by `recurrence_id`. */
    val overrides: List<SeriesOverrideDto> = emptyList(),
)

/**
 * One per-occurrence exception recorded against a series — the backend's `SeriesOverrideView`.
 *
 * **[dtstartLocal] here is the MOVED time**, while [SeriesInputsDto.dtstartLocal] one nesting level up
 * is the anchor: same wire key, two meanings. The domain renames this one `movedToLocal` so the two
 * can never be confused again — do not undo that on the way through.
 */
@Serializable
data class SeriesOverrideDto(
    /**
     * The rule slot this exception applies to, identified by the firing's **original** local wall
     * time (RFC 5545 `RECURRENCE-ID`) — not the moved time, and not a bare date.
     */
    @SerialName("recurrence_id") val recurrenceId: String? = null,
    /**
     * Whether this firing is cancelled. **Always `false` on the wire today**: the only writer is
     * `TaskRepository::cancel_recurrence_instance` and no route calls it, while the `scope=this`
     * patch that mints every reachable override hardcodes `false`. Decoded anyway — a cancelled
     * firing is still *emitted* by the expander, flagged, not dropped.
     */
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    /** Rescheduled local start; `null` when the exception did not move the firing. */
    @SerialName("dtstart_local") val dtstartLocal: String? = null,
)

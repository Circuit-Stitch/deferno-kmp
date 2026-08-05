package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

/**
 * The stored expansion inputs behind a recurring definition — the domain projection of the wire's
 * additive `series` block (ADR-0053 decision 2, `Circuit-Stitch/Deferno#643`). Paired with a
 * [Recurrence] these are everything [expandOccurrenceGrid] needs to reproduce the server's
 * [Occurrence grid] offline, on any window, past or future, with the network gone.
 *
 * They mirror the backend's `SeriesInputs` field for field (`backend/src/models/recurrence.rs:365-377`),
 * and two of its invariants are the whole reason the block exists:
 *
 * - **[anchorLocal] is a WALL TIME and it does not move.** It is the series' frozen `DTSTART`, not the
 *   live `complete_by`: on a recurring definition `complete_by` is a [RecurrenceCursor] that walks
 *   forward on every mark-done while the anchor stays put, so from the first completion onward the two
 *   diverge and the cursor can never substitute for it. Deriving a stride's phase from the cursor is
 *   off by however far it has walked.
 * - **[tzid] is the zone the series was FROZEN in** — never the account's current setting, never the
 *   device's. Someone who moves country keeps the grid they scheduled. That is also why this is the
 *   raw IANA identifier rather than a resolved `TimeZone`: a token this build's tzdb cannot place must
 *   survive the trip and surface as [ExpansionRefusal.UnknownTimeZone], not throw at the mapper — the
 *   same lossless-round-trip posture [Cadence.Unmodelled] takes for an unknown cadence token (#382).
 *
 * **Absent, not empty.** The backend elides the whole block when no series row backs an item rather
 * than repairing one on a read (`series_repair` re-anchors from the live cursor, which would bake the
 * walked cursor in as the anchor — the exact drift these inputs exist to make visible). So a `null`
 * `SeriesInputs` means "this device cannot reproduce that grid", never "that grid is empty".
 */
data class SeriesInputs(
    /** The frozen `DTSTART` — a local wall time in [tzid], **never** an [Instant]. */
    val anchorLocal: LocalDateTime,
    /** The IANA zone the series was created in, e.g. `America/Los_Angeles`. */
    val tzid: String,
    /**
     * The [Segment] bound — set **only** when a series was split ("this and following"), and
     * **EXCLUSIVE**: defernodate drops any firing whose instant is `>= until_utc`
     * (`defernodate/src/expand.rs:76-82`).
     *
     * _Avoid_ conflating this with [RecurrenceBound.OnDate], which is the *rule's* `UNTIL` and is
     * **INCLUSIVE** (`recurrence.rs:307`, `:317-327`). The two bounds have opposite inclusivity and a
     * superseded segment carries both at once.
     */
    val untilUtc: Instant? = null,
    /**
     * Local wall times excluded from the expansion (RFC 5545 `EXDATE`). Nothing populates these
     * server-side today, so this is correct for free the day something does.
     *
     * **An excluded firing still consumes a `COUNT`.** RFC 5545 excludes *after* generation, so
     * `COUNT=10` with one EXDATE yields nine firings, not ten (verified against `defernodate 0.2.0`).
     */
    val exdates: List<LocalDateTime> = emptyList(),
    /** Per-instance exceptions, keyed by the firing's **original** local wall time. */
    val overrides: List<SeriesOverride> = emptyList(),
)

/**
 * One per-instance exception recorded against a series — the projection of the backend's
 * `SeriesOverrideView` (`backend/src/models/recurrence.rs:381-389`).
 *
 * **[recurrenceId] is the ORIGINAL local wall time, not the moved one and not a date.** It is the RFC
 * 5545 `RECURRENCE-ID`, and it is how the exception finds the rule slot it belongs to
 * (`defernodate/src/expand.rs:67-69`). An expander that keyed on the moved time, or that threw the
 * wall time away and kept only a date, would lose the slot a rescheduled instance came from.
 */
data class SeriesOverride(
    /** The rule slot this exception applies to (RFC 5545 `RECURRENCE-ID`). */
    val recurrenceId: LocalDateTime,
    /** Whether this firing is cancelled. A cancelled firing is still **emitted**, flagged — see [Firing]. */
    val isCancelled: Boolean = false,
    /** Rescheduled local start; `null` when the firing was not moved. */
    val movedToLocal: LocalDateTime? = null,
)

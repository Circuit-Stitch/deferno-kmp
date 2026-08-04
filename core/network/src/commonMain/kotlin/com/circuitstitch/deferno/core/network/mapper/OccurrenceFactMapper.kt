package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.completionResolution
import com.circuitstitch.deferno.core.network.dto.ChoreOccurrenceDto
import com.circuitstitch.deferno.core.network.dto.EventOccurrenceDto
import com.circuitstitch.deferno.core.network.dto.HabitOccurrenceDto
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The three per-kind occurrence-list DTOs → the one kind-neutral [OccurrenceFact] (#390, ADR-0053
 * decision 4) — "condense at the edge" (ADR-0011) applied to a dated firing.
 *
 * There are three mappers rather than one because the three endpoints genuinely return three different
 * structs, not three flavours of the same one: a habit row has no id and no status at all, a chore row
 * is a *derived view* with a `segment_id`, and only the event row is the unified `Occurrence`. What
 * they condense *to* is uniform, which is the point — after this seam nothing downstream dispatches on
 * kind to read a firing.
 *
 * **Two rules hold across all three.**
 *
 * 1. **[definitionId] comes from the caller, never off the row.** Each payload carries an id of its own
 *    (`habit_id` / `segment_id` / `parent_id`), and after a recurrence-rule change that id is the
 *    *Segment* that owns the date while the list itself was addressed by the chain's **Head** (#574).
 *    The fact key is `(kind, definitionId, date)` — the same identity the write path builds with
 *    `OccurrenceTargets.of`, which keys on the item id (the Head), never the series or segment id.
 *    Keying on the row's own id instead would produce facts the write path can never match, and a
 *    replayed write would 404 — which the outbox treats as success, so the write would evaporate.
 *
 * 2. **A reading is never stored.** Only the resolution and its timestamps cross this seam. The
 *    Scheduled-vs-Missed split is a function of *today* and is derived at render time by
 *    `resolveOccurrenceState`; the chore endpoint's two derived arms are dropped on the floor here
 *    (see [ChoreOccurrenceDto.toFact]).
 *
 * [kind] is threaded in rather than hard-coded per mapper for the same reason it is threaded through
 * `OccurrenceTargets.of`: it is one value the caller already holds and passes to the store, the outbox
 * target and the fact alike, so it stays a single source rather than three literals that can drift.
 */

/**
 * A habit firing → its fact. A habit stores **no status**: done-ness is `done_at != null` and nothing
 * else (`backend/src/models/occurrence.rs` → `HabitOccurrence`), so the resolution is synthesised
 * rather than condensed — a ticked firing resolves through the shared punctuality split
 * [completionResolution], an unticked one is [OccurrenceResolution.Scheduled] ("a row exists that
 * records no progress"), which is a fact and not the derived reading of the same name.
 *
 * [completeBy] is a parameter because the wire row has no deadline field at all — the deadline lives on
 * the habit *definition*. The caller supplies the deadline that applied **to this date**, and it is
 * stored on the fact alongside the resolution precisely because the definition's live `complete_by` is
 * a moving cursor that will have walked on. With no deadline supplied the firing cannot be late:
 * [completionResolution] reads a null bound as on time, since there is nothing to be late against.
 */
fun HabitOccurrenceDto.toFact(
    kind: ItemKind,
    definitionId: String,
    completeBy: Instant? = null,
): OccurrenceFact {
    val doneAtInstant = doneAt?.let { Instant.parse(it) }
    return OccurrenceFact(
        kind = kind,
        definitionId = definitionId,
        date = LocalDate.parse(date),
        resolution = doneAtInstant?.let { completionResolution(it, completeBy) }
            ?: OccurrenceResolution.Scheduled,
        doneAt = doneAtInstant,
        completeBy = completeBy,
    )
}

/**
 * A chore view row → its fact, **or `null` when the row is not a fact at all**.
 *
 * The chore list is a derived view over the whole requested window, so it emits a row for every date —
 * including dates the server holds no record for. `scheduled` and `missed` are exactly those dates
 * (`backend/src/handlers/occurrences.rs:488-495`) and both condense to `null` via
 * [DerivedChoreOccurrenceStatusWire.toResolutionOrNull]: the caller writes no row, and the
 * Scheduled-vs-Missed reading is derived at render time against the user's **local** today rather than
 * the `Utc::now()` the server used. Absence inside synced coverage is the honest record for a date with
 * nothing on it.
 *
 * The punctuality split is *not* recomputed here: the server already decided it
 * (`decide_chore_done_status`, inclusive bound) and shipped it as `done_on_time`/`done_late`, so
 * re-deriving it client-side could only introduce a disagreement. [ChoreOccurrenceDto.completedAt] is
 * the same concept the other two kinds spell `done_at`.
 *
 * [ChoreOccurrenceDto.segmentId] is deliberately dropped: it addresses a per-occurrence *write* for
 * this date, which is the outbox's concern, not the cache's — the fact stays keyed on the Head.
 */
fun ChoreOccurrenceDto.toFact(kind: ItemKind, definitionId: String): OccurrenceFact? {
    val resolution = status.toResolutionOrNull() ?: return null
    return OccurrenceFact(
        kind = kind,
        definitionId = definitionId,
        date = LocalDate.parse(scheduledDate),
        resolution = resolution,
        doneAt = completedAt?.let { Instant.parse(it) },
        completeBy = Instant.parse(completeBy),
    )
}

/**
 * An event firing → its fact. Total: `GET /events/{id}/occurrences` returns **only stored rows**
 * (`backend/src/handlers/event_occurrences.rs:57-60`), so every element is a fact and there is no
 * `null` arm — a row reading `scheduled` is a written row that records no progress, which is why
 * [OccurrenceStatusWire.toResolution] is total where its chore sibling is not.
 *
 * As with the chore, the punctuality split is the server's (`done_on_time`/`done_late` are stored
 * statuses) and is condensed rather than recomputed; `dropped` is the event spelling of `Skipped`. The
 * sixteen-field payload's comments, attachments and per-occurrence overrides are render concerns and do
 * not belong on a fact, so they stop here.
 */
fun EventOccurrenceDto.toFact(kind: ItemKind, definitionId: String): OccurrenceFact = OccurrenceFact(
    kind = kind,
    definitionId = definitionId,
    date = LocalDate.parse(scheduledDate),
    resolution = status.toResolution(),
    doneAt = doneAt?.let { Instant.parse(it) },
    completeBy = Instant.parse(completeBy),
)

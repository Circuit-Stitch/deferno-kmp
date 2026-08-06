package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.OccurrenceDto
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
import com.circuitstitch.deferno.core.network.mapper.asEventOrNull
import com.circuitstitch.deferno.core.network.mapper.asHabitOrNull
import com.circuitstitch.deferno.core.network.mapper.asTaskOrNull
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.mapper.toResolution
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.http.appendPathSegments
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The kind-neutral single-item read — `GET /items/{id}` (#383).
 *
 * **This is the client's first call to that route.** Task detail has always refreshed through
 * `GET /tasks/{id}`, and the only `items/…` paths this client built were `convert`, `history`, `move`
 * and `attachments`. There is no per-kind detail route to widen: the shipped contract has no `get` on
 * `/habits/{id}`, `/chores/{id}` or `/events/{id}` at all, only `delete` and `patch`. So a recurring
 * definition's detail read is new code, not a widened call.
 *
 * It carries three things the `/items` snapshot cannot, which is exactly why it exists:
 * `today_occurrence` (a dated answer), `series_chain` (the per-[[Segment]] eras the snapshot collapses
 * with `DropSuperseded`), and `origin_label`. Everything else the detail renders is already cached —
 * ADR-0054 put the `series` block on every snapshot row precisely so a definition reaches its
 * [[Occurrence grid]] cold, with no round trip.
 */
interface ItemDetailRemoteSource {

    /** Read one item by [ref]. [RemoteSnapshot.Unavailable] when the network is gone — never throws. */
    suspend fun fetch(ref: ItemRef): RemoteSnapshot<ItemDetailRead>
}

/**
 * One detail read, condensed at the edge (ADR-0011) — no wire type crosses this seam.
 *
 * The split is by *cacheability*, and it is load-bearing. [habit]/[chore]/[event]/[task] are records
 * and are upserted into the local stores. [todayFact] is a stored resolution and belongs in the fact
 * table. [chain] and [originLabel] are neither: a chain era can never be refreshed cold (the snapshot
 * drops superseded segments), so caching one would be a lie no later sync could correct.
 *
 * **The four record fields carry the CONCRETE rows, not the [RecurringDefinition] projection**, and
 * exactly one is ever non-null (the response's `type` discriminator picks it). The projection is
 * lossy by design — it carries what a read-only detail *renders* and drops `org_slug`, `date_created`,
 * a Chore's `cadence_mode`, an Event's times — so a repository handed one can only ever *merge* it
 * onto a row it already has, and has nothing to write when it has none. That is not hypothetical: the
 * detail read is the one path that can answer for an item this device never synced (a deep link, a row
 * outside the snapshot window, an item created on another device), and dropping the record there would
 * render "not found" while holding the server's answer — the exact defect #383 exists to fix. The
 * wire mappers already build the full row (`asHabitOrNull()` and friends), which is the same row
 * `ItemSync` reconciles a snapshot into, so carrying it through costs nothing and makes the upsert a
 * plain insert-or-replace.
 *
 * **[answeredForToday] is not the same as `todayFact != null`.** The server answers for today either
 * way; it signals "no stored record" with an all-zeroes id rather than by omitting the field. That
 * distinction is the entire point of [[Occurrence coverage]] — a day the server answered for with
 * nothing recorded is *unresolved*, while a day it was never asked about is *unknown* — so coverage is
 * recorded on this flag and never on the presence of a fact.
 */
data class ItemDetailRead(
    val habit: Habit? = null,
    val chore: Chore? = null,
    val event: Event? = null,
    val task: Task? = null,
    val todayFact: OccurrenceFact? = null,
    val answeredForToday: Boolean = false,
    val chain: SeriesChain? = null,
    val originLabel: String? = null,
)

/**
 * The production [ItemDetailRemoteSource] over the shared Deferno [HttpClient].
 *
 * Modelled on `KtorItemConverter`, the one existing production decode of a single polymorphic
 * [ItemView], down to the exhaustive four-arm `when` — the discriminator is `type`, so exactly one arm
 * matches and the `!!`s cannot fire.
 */
internal class KtorItemDetailRemoteSource(
    private val client: HttpClient,
) : ItemDetailRemoteSource {

    override suspend fun fetch(ref: ItemRef): RemoteSnapshot<ItemDetailRead> =
        client.requestApi<ItemView> {
            url { appendPathSegments("items", ref.id) }
        }.map { it.toItemDetailRead(ref.kind) }.asSnapshot()
}

/**
 * The polymorphic detail response → [ItemDetailRead].
 *
 * [requestedKind] is the caller's, not the row's, and it is used **only** to key the fact — the same
 * rule `OccurrenceFactMapper` states and for the same reason: the fact key must match what the write
 * path builds through `OccurrenceTargets.of`, which keys on the item id the caller addressed. Reading
 * the kind off the response instead would be equivalent here, but keying off the caller keeps one
 * source rather than two that can drift after a convert.
 */
internal fun ItemView.toItemDetailRead(requestedKind: ItemKind): ItemDetailRead = when (this) {
    is ItemView.Task -> ItemDetailRead(
        task = asTaskOrNull()!!,
        chain = seriesChain.toDomain(),
        originLabel = originLabel,
    )
    is ItemView.Habit -> ItemDetailRead(
        habit = asHabitOrNull()!!,
        chain = seriesChain.toDomain(),
        originLabel = originLabel,
        todayFact = todayOccurrence.toFactOrNull(requestedKind, id),
        answeredForToday = todayOccurrence != null,
    )
    is ItemView.Chore -> ItemDetailRead(
        chore = asChoreOrNull()!!,
        chain = seriesChain.toDomain(),
        originLabel = originLabel,
        todayFact = todayOccurrence.toFactOrNull(requestedKind, id),
        answeredForToday = todayOccurrence != null,
    )
    is ItemView.Event -> ItemDetailRead(
        event = asEventOrNull()!!,
        chain = seriesChain.toDomain(),
        originLabel = originLabel,
        todayFact = todayOccurrence.toFactOrNull(requestedKind, id),
        answeredForToday = todayOccurrence != null,
    )
}

/**
 * `today_occurrence` → the **stored** half of that firing, or `null` when there is nothing stored.
 *
 * Two arms return `null`, and they mean different things to the caller — which is why
 * [ItemDetailRead.answeredForToday] is a separate flag rather than being inferred from this being
 * `null`:
 *
 * - **the all-zeroes placeholder.** The backend fills `id` with a zero UUID when no record exists for
 *   the date, rather than omitting the field, and the contract says so in as many words: *"When nothing
 *   has been recorded for the date the status is `scheduled` and the id is all-zeroes, signalling a
 *   placeholder rather than a stored record."* Writing a fact from it would manufacture a resolution
 *   the server never recorded.
 * - **an unparseable date.** A fact keyed on a date we cannot read is worse than no fact.
 *
 * Note what is deliberately *not* consulted: the wire `status`. On a placeholder it reads `scheduled`,
 * which is a **reading** and not a stored value, and ADR-0053 forbids that ever reaching a table. The
 * Scheduled-vs-Missed split is re-derived at render time by `resolveOccurrenceState`.
 */
private fun OccurrenceDto?.toFactOrNull(kind: ItemKind, definitionId: String): OccurrenceFact? {
    val dto = this ?: return null
    if (dto.id == PLACEHOLDER_OCCURRENCE_ID) return null
    val date = runCatching { LocalDate.parse(dto.scheduledDate) }.getOrNull() ?: return null
    return OccurrenceFact(
        kind = kind,
        definitionId = definitionId,
        date = date,
        resolution = dto.status.toResolution(),
        // [OccurrenceDto] is the INLINE projection of the wire `Occurrence` and carries no `done_at`
        // — the punctuality split is already condensed into `status` (`done_on_time`/`done_late`), so
        // nothing is lost here. The full sixteen-field struct is [EventOccurrenceDto]'s shape.
        doneAt = null,
        // A malformed timestamp degrades to "no deadline" rather than taking the fact down: the
        // resolution is the load-bearing half and this only refines it.
        completeBy = dto.completeBy?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

/**
 * The backend's "no stored record" sentinel for `today_occurrence`. A literal rather than a parsed UUID
 * because the wire value is compared, never constructed, and this client has no UUID type.
 */
private const val PLACEHOLDER_OCCURRENCE_ID = "00000000-0000-0000-0000-000000000000"

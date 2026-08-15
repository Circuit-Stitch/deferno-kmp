package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.item.CachedItem
import com.circuitstitch.deferno.core.data.item.ItemDetailRemoteSource
import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.item.asKindRow
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import com.circuitstitch.deferno.core.model.toDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The kind-neutral read of one recurring definition (#383) — a [[Habit]], [[Chore]] or [[Event]].
 *
 * **Why this exists over the local store.** It does two things the store does not, and both are the
 * reason #383 could not just lift the gates: it hydrates through the kind-neutral `GET /items/{id}`
 * (there *is* no per-kind detail route), and it lands the detail read's dated answer as a **fact plus
 * coverage** rather than as a reading, which is the ADR-0053 decision-4 rule the rest of this client
 * already obeys and nothing was yet feeding.
 *
 * Its third job was fanning out over kind so a caller holding an [ItemRef] needed no `when`. The store
 * absorbed that at #422, which is the point of the flip.
 *
 * It is a *read* seam only. Every write on a recurring definition — the rule, the per-field patches,
 * delete — is #378/#388/#389's, and the existing write seams are all `TaskId`-typed for now.
 */
interface DefinitionRepository {

    /**
     * The cached definition, re-emitting on every local write (ADR-0001 observe-via-Flow). `null` while
     * the definition is unknown to this device — which is a normal cold-start state, not an error.
     *
     * Emits `null` immediately for a [ItemKind.Task] ref: a Task is not a definition, and answering
     * "not found" is the honest reading rather than throwing at a caller that holds a generic ref.
     */
    fun observe(ref: ItemRef): Flow<RecurringDefinition?>

    /**
     * Best-effort detail refresh for [ref].
     *
     * Upserts the definition into its per-kind store, records the day's stored resolution into the fact
     * table **and** that day into [[Occurrence coverage]], and returns the read-only extras that must
     * never be cached. Returns `null` when the network is gone — the cached row still renders, which is
     * the whole offline-first posture (ADR-0001).
     *
     * **It takes no date, deliberately.** The request carries none: the server answers for its own
     * notion of today, in the account's zone, and says which day that was. Accepting a date here would
     * invite a caller to believe the two agree — and writing coverage at the caller's day rather than
     * the server's is precisely how a day nothing was ever asked about gets marked synced, which the
     * Calendar then renders as a confident Missed.
     */
    suspend fun hydrate(ref: ItemRef): DefinitionExtras?

    /** The inert implementation — every read empty, every hydrate a no-op. For previews and tests. */
    companion object {
        val NONE: DefinitionRepository = object : DefinitionRepository {
            override fun observe(ref: ItemRef): Flow<RecurringDefinition?> = flowOf(null)
            override suspend fun hydrate(ref: ItemRef): DefinitionExtras? = null
        }
    }
}

/**
 * The half of a detail read that may never reach a table.
 *
 * [chain] is chain-collapsed on the snapshot (`SegmentRetention::DropSuperseded`), so a cached era
 * could never be refreshed cold — ADR-0053 accepts that limit knowingly rather than papering over it
 * with a stale cache. [originLabel] is server-derived and detail-only.
 */
data class DefinitionExtras(
    val chain: SeriesChain? = null,
    val originLabel: String? = null,
)

/**
 * The offline-first [DefinitionRepository]: reads come from the local stores, always; the network only
 * refreshes them (ADR-0001).
 */
class OfflineDefinitionRepository(
    private val items: ItemLocalStore,
    private val remote: ItemDetailRemoteSource,
    private val facts: OccurrenceFactLocalStore,
    private val coverage: OccurrenceCoverageLocalStore,
    private val recipe: KindRecipe = ParityRecipe,
) : DefinitionRepository {

    /**
     * The `when (ref.kind)` fan-out over three stores is gone since #422: one store answers by id and
     * the stored row says which kind it is. The ref's own kind is no longer consulted, which makes a
     * stale one harmless — a ref whose kind the server has since converted used to read `null` because
     * it queried the wrong table.
     */
    override fun observe(ref: ItemRef): Flow<RecurringDefinition?> =
        items.observe(ref.id).map { it?.toDefinitionOrNull() }

    private fun CachedItem.toDefinitionOrNull(): RecurringDefinition? = when (val row = asKindRow(recipe)) {
        // A Task is not a definition, and answering "not found" is the honest reading rather than
        // throwing at a caller that holds a generic ref.
        is KindRow.OfTask -> null
        is KindRow.OfHabit -> row.habit.toDefinition()
        is KindRow.OfChore -> row.chore.toDefinition()
        is KindRow.OfEvent -> row.event.toDefinition()
    }

    override suspend fun hydrate(ref: ItemRef): DefinitionExtras? {
        val read = when (val snapshot = remote.fetch(ref)) {
            is RemoteSnapshot.Available -> snapshot.value
            RemoteSnapshot.Unavailable -> return null
        }

        // Insert-or-replace the concrete row, exactly as ItemSync reconciles a snapshot row — the
        // detail body IS the snapshot shape plus derived fields, so the same mapper builds the same
        // record. Critically this also lands a definition this device has NEVER cached (a deep link, a
        // row outside the snapshot window), which is the one case the detail read exists to answer and
        // the one a merge-onto-cached could not: it would drop the record and render "not found" while
        // holding the server's answer.
        read.habit?.let { items.upsert(CachedItem(recipe.read(it), ItemKind.Habit)) }
        read.chore?.let { items.upsert(CachedItem(recipe.read(it), ItemKind.Chore)) }
        read.event?.let { items.upsert(CachedItem(recipe.read(it), ItemKind.Event)) }

        // A stored resolution is a fact and is cached. An all-zeroes PLACEHOLDER is not — the server
        // answered "nothing recorded for that date", which is a different statement from a resolution
        // of `scheduled`, and writing one would manufacture a record the server never had.
        read.todayFact?.let { facts.upsert(it) }

        // Coverage is recorded on having ASKED, not on having got a fact. That asymmetry is the whole
        // reason the two tables are separate: inside coverage an absent resolution is evidence
        // (unresolved), outside it the same absence is only ignorance (unknown). Recording it only
        // when a fact came back would leave every never-yet-resolved day reading Unknown forever.
        //
        // Recorded at the date the SERVER answered for, never at `today`. The request carries no date,
        // so the server picks the day in the account's zone while `today` comes from the device's; when
        // they diverge, recording `today` marks a day nothing was ever asked about as synced, and the
        // Calendar later renders that as a confident Missed. See ItemDetailRead.answeredForDate.
        read.answeredForDate?.let { answered ->
            coverage.record(OccurrenceCoverage(ref.kind, ref.id, from = answered, to = answered))
        }

        return DefinitionExtras(chain = read.chain, originLabel = read.originLabel)
    }
}

package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.item.ItemDetailRemoteSource
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.toDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The kind-neutral read of one recurring definition (#383) — a [[Habit]], [[Chore]] or [[Event]].
 *
 * **Why this exists over the three local stores**, which #171 deliberately stripped their repository
 * wrappers from. It does three things none of them can, and all three are the reason #383 could not
 * just lift the gates: it fans out over kind so a caller holding an [ItemRef] needs no `when`; it
 * hydrates through the kind-neutral `GET /items/{id}` (there *is* no per-kind detail route); and it
 * lands the detail read's dated answer as a **fact plus coverage** rather than as a reading, which is
 * the ADR-0053 decision-4 rule that the rest of this client already obeys and nothing was yet feeding.
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
     * Best-effort detail refresh for [ref], answering for [today].
     *
     * Upserts the definition into its per-kind store, records today's stored resolution into the fact
     * table **and** the day into [[Occurrence coverage]], and returns the read-only extras that must
     * never be cached. Returns `null` when the network is gone — the cached row still renders, which is
     * the whole offline-first posture (ADR-0001).
     */
    suspend fun hydrate(ref: ItemRef, today: LocalDate): DefinitionExtras?

    /** The inert implementation — every read empty, every hydrate a no-op. For previews and tests. */
    companion object {
        val NONE: DefinitionRepository = object : DefinitionRepository {
            override fun observe(ref: ItemRef): Flow<RecurringDefinition?> = flowOf(null)
            override suspend fun hydrate(ref: ItemRef, today: LocalDate): DefinitionExtras? = null
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
    private val habits: HabitLocalStore,
    private val chores: ChoreLocalStore,
    private val events: EventLocalStore,
    private val remote: ItemDetailRemoteSource,
    private val facts: OccurrenceFactLocalStore,
    private val coverage: OccurrenceCoverageLocalStore,
) : DefinitionRepository {

    override fun observe(ref: ItemRef): Flow<RecurringDefinition?> = when (ref.kind) {
        ItemKind.Task -> flowOf(null)
        ItemKind.Habit -> habits.observe(HabitId(ref.id)).map { it?.toDefinition() }
        ItemKind.Chore -> chores.observe(ChoreId(ref.id)).map { it?.toDefinition() }
        ItemKind.Event -> events.observe(EventId(ref.id)).map { it?.toDefinition() }
    }

    override suspend fun hydrate(ref: ItemRef, today: LocalDate): DefinitionExtras? {
        val read = when (val snapshot = remote.fetch(ref)) {
            is RemoteSnapshot.Available -> snapshot.value
            RemoteSnapshot.Unavailable -> return null
        }

        read.definition?.let { upsert(it) }

        // A stored resolution is a fact and is cached. An all-zeroes PLACEHOLDER is not — the server
        // answered "nothing recorded for that date", which is a different statement from a resolution
        // of `scheduled`, and writing one would manufacture a record the server never had.
        read.todayFact?.let { facts.upsert(it) }

        // Coverage is recorded on having ASKED, not on having got a fact. That asymmetry is the whole
        // reason the two tables are separate: inside coverage an absent resolution is evidence
        // (unresolved), outside it the same absence is only ignorance (unknown). Recording it only
        // when a fact came back would leave every never-yet-resolved day reading Unknown forever.
        if (read.answeredForToday) {
            coverage.record(OccurrenceCoverage(ref.kind, ref.id, from = today, to = today))
        }

        return DefinitionExtras(chain = read.chain, originLabel = read.originLabel)
    }

    /**
     * Upsert the refreshed definition back into its own per-kind store.
     *
     * It goes through the concrete store rather than a kind-neutral write because the stores hold the
     * concrete rows — [RecurringDefinition] is a read projection and deliberately does not carry every
     * column (a Chore's cadence mode, an Event's end time). So the refreshed *detail* fields are merged
     * onto the cached row rather than replacing it: a projection round-trip would silently blank the
     * columns the projection never carried.
     */
    private suspend fun upsert(definition: RecurringDefinition) {
        when (definition.kind) {
            ItemKind.Task -> Unit
            ItemKind.Habit -> habits.get(HabitId(definition.id))?.let { cached ->
                habits.upsert(
                    cached.copy(
                        title = definition.title,
                        definitionState = definition.definitionState,
                        description = definition.description,
                        labels = definition.labels,
                        recurrence = definition.recurrence,
                        completeBy = definition.cursorAt,
                        seriesId = definition.seriesId,
                        series = definition.series,
                        blocked = definition.blocked,
                        isBlocker = definition.isBlocker,
                        hydration = definition.hydration,
                    ),
                )
            }
            ItemKind.Chore -> chores.get(ChoreId(definition.id))?.let { cached ->
                chores.upsert(
                    cached.copy(
                        title = definition.title,
                        definitionState = definition.definitionState,
                        description = definition.description,
                        labels = definition.labels,
                        recurrence = definition.recurrence,
                        completeBy = definition.cursorAt,
                        seriesId = definition.seriesId,
                        series = definition.series,
                        blocked = definition.blocked,
                        isBlocker = definition.isBlocker,
                        hydration = definition.hydration,
                    ),
                )
            }
            ItemKind.Event -> events.get(EventId(definition.id))?.let { cached ->
                events.upsert(
                    cached.copy(
                        title = definition.title,
                        definitionState = definition.definitionState,
                        description = definition.description,
                        labels = definition.labels,
                        recurrence = definition.recurrence,
                        completeBy = definition.cursorAt,
                        seriesId = definition.seriesId,
                        series = definition.series,
                        blocked = definition.blocked,
                        isBlocker = definition.isBlocker,
                        hydration = definition.hydration,
                    ),
                )
            }
        }
    }
}

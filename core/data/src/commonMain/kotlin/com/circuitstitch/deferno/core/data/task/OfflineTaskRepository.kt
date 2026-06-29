package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The offline-first [TaskRepository] (ADR-0001, #22): the local store is the single source of truth,
 * reads are its `Flow`s, and the network only ever *writes through* the store via [refresh]/[hydrate].
 *
 * **Cold sync ([refresh]) — delegated to [ItemSync].** As of ADR-0034 (#226) the cold snapshot migrated
 * from the legacy task-only `GET /tasks` to the item-wide `GET /items`, so a refresh now reconciles
 * *every* kind (Task/Habit/Chore/Event) into its store — honoring the server-windowed done-visibility
 * window — not just Tasks. That cross-kind reconcile lives in [ItemSync]; this repository just triggers
 * it (the trigger seam stays [TaskRepository.refresh] so its callers are unchanged). The Task reads
 * below are unaffected — they still observe the Task store.
 *
 * **Hydration ([hydrate]).** Opening a Task pulls its full detail (`GET /tasks/{id}`) and upserts it,
 * upgrading the cached row summary -> full; a missing/failed detail is a no-op (the summary stays).
 *
 * **Search ([search]) — offline (#311, ADR-0042).** Reverses the legacy online-only `/tasks/search` pull:
 * global search now runs as a local read over the four per-kind caches (the same stores [ItemSync] feeds),
 * so it works with no network. The recurring stores are read for the cross-kind text/label match; the
 * status/date/attachment filters are Task-scoped (recurring kinds carry no [WorkingState] / attachment
 * rollup). Results are not written into the observed list — search stays a separate read surface (ADR-0001).
 */
class OfflineTaskRepository(
    private val localStore: TaskLocalStore,
    private val remoteSource: TaskRemoteSource,
    private val itemSync: ItemSync,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    // The zone used to project an item's `completeBy` Instant to a calendar day for the date-range filter
    // (#311). Defaulted to the device zone so production DI needn't provide one; a test pins it.
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = localStore.observeActive()

    override fun observeTask(id: TaskId): Flow<Task?> = localStore.observe(id)

    override suspend fun refresh() = itemSync.refresh()

    override suspend fun hydrate(id: TaskId) {
        val detail = remoteSource.fetch(id) ?: return
        // The `/tasks/{id}` detail does not carry the server-computed subtree counts (those are an
        // `/items`-snapshot computation, ADR-0034) — so preserve the cached counts the snapshot set,
        // rather than blanking a collapsed tree node's progress badge on detail-open (#226/#227).
        val existing = localStore.get(id)
        localStore.upsert(
            detail.copy(
                descendantDone = detail.descendantDone ?: existing?.descendantDone,
                descendantTotal = detail.descendantTotal ?: existing?.descendantTotal,
            ),
        )
    }

    /**
     * Global search (#311, ADR-0042): an **offline** local read over the four per-kind caches. A query
     * with no constraint at all (blank term + no filters) returns empty — the overlay's "type to search"
     * state, never a dump of the whole cache. Otherwise every cached item is read, filtered by
     * term/status/label/date/attachment (see [SearchRow.matches]), projected to a [SearchHit], and sorted.
     * Results are **not** upserted into the observed list — search is a separate read surface (ADR-0001).
     */
    override suspend fun search(query: TaskSearchQuery): List<SearchHit> {
        if (!query.hasRunnableConstraint()) return emptyList()
        return collectSearchRows()
            .filter { it.matches(query) }
            .map { it.hit }
            .sortedWith(query.sort.comparator())
    }

    /** Snapshot the four caches once and flatten them to the common [SearchRow] shape. */
    private suspend fun collectSearchRows(): List<SearchRow> = buildList {
        localStore.observeActive().first().forEach { add(it.toSearchRow()) }
        habitStore.observeActive().first().forEach { add(it.toSearchRow()) }
        choreStore.observeActive().first().forEach { add(it.toSearchRow()) }
        eventStore.observeActive().first().forEach { add(it.toSearchRow()) }
    }

    private fun SearchRow.matches(query: TaskSearchQuery): Boolean {
        val term = query.query.trim()
        if (term.isNotEmpty() &&
            !hit.title.contains(term, ignoreCase = true) &&
            description?.contains(term, ignoreCase = true) != true
        ) {
            return false
        }
        // Status is Task-scoped: a recurring row has no WorkingState, so any status filter excludes it.
        if (query.statuses.isNotEmpty() && workingState !in query.statuses) return false
        // Every selected label must be present (AND).
        if (query.labels.isNotEmpty() && !labels.containsAll(query.labels)) return false
        if (query.fromDate != null || query.toDate != null) {
            val day = hit.completeBy?.toLocalDateTime(timeZone)?.date ?: return false
            if (query.fromDate != null && day < query.fromDate) return false
            if (query.toDate != null && day > query.toDate) return false
        }
        if (query.hasAttachment && hit.attachmentCount == 0) return false
        return true
    }

    private fun SearchSort.comparator(): Comparator<SearchHit> = when (this) {
        SearchSort.Relevance -> Comparator { _, _ -> 0 } // keep the cross-kind read order
        SearchSort.TitleAsc -> compareBy { it.title.lowercase() }
        // Soonest deadline first; hits without a deadline sort last (nulls last).
        SearchSort.DeadlineAsc -> compareBy(nullsLast()) { it.completeBy }
        // Biggest attachments first; hits without attachments (size 0) sort last.
        SearchSort.AttachmentSizeDesc -> compareByDescending { it.attachmentTotalSize }
    }
}

/**
 * A cached item projected for search (#311): the [hit] the result row renders, plus the three fields the
 * filters read but the row never shows — [description] (free-text match), [labels] (label filter), and
 * [workingState] (`null` for the recurring kinds, which is what makes the status filter Task-scoped). The
 * sort runs on [hit] alone, so attachment/deadline/title ordering needs nothing beyond the displayed shape.
 */
private data class SearchRow(
    val hit: SearchHit,
    val description: String?,
    val labels: List<String>,
    val workingState: WorkingState?,
)

private fun Task.toSearchRow() = SearchRow(
    hit = SearchHit(
        id = id.value,
        kind = ItemKind.Task,
        title = title,
        isTerminal = workingState.isTerminal,
        blocked = blocked,
        completeBy = completeBy,
        deadlineTimeOfDay = deadlineTimeOfDay,
        ref = ref,
        attachmentCount = attachmentCount,
        attachmentTotalSize = attachmentTotalSize,
    ),
    description = description,
    labels = labels,
    workingState = workingState,
)

// Recurring kinds: no WorkingState (status filter excludes them), no attachment rollup (#311 is Task-only),
// terminal == Archived (the recurring analog of a Done/Dropped Task). Event projects its start-of-day clock.
private fun Habit.toSearchRow() = recurringSearchRow(
    id = id.value, kind = ItemKind.Habit, title = title, description = description,
    labels = labels, state = definitionState, blocked = blocked,
    completeBy = completeBy, timeOfDay = deadlineTimeOfDay, ref = ref,
)

private fun Chore.toSearchRow() = recurringSearchRow(
    id = id.value, kind = ItemKind.Chore, title = title, description = description,
    labels = labels, state = definitionState, blocked = blocked,
    completeBy = completeBy, timeOfDay = deadlineTimeOfDay, ref = ref,
)

private fun Event.toSearchRow() = recurringSearchRow(
    id = id.value, kind = ItemKind.Event, title = title, description = description,
    labels = labels, state = definitionState, blocked = blocked,
    completeBy = completeBy, timeOfDay = startTimeOfDay, ref = ref,
)

private fun recurringSearchRow(
    id: String,
    kind: ItemKind,
    title: String,
    description: String?,
    labels: List<String>,
    state: DefinitionState,
    blocked: Boolean,
    completeBy: Instant?,
    timeOfDay: LocalTime?,
    ref: String?,
) = SearchRow(
    hit = SearchHit(
        id = id,
        kind = kind,
        title = title,
        isTerminal = state == DefinitionState.Archived,
        blocked = blocked,
        completeBy = completeBy,
        deadlineTimeOfDay = timeOfDay,
        ref = ref,
        attachmentCount = 0,
        attachmentTotalSize = 0,
    ),
    description = description,
    labels = labels,
    workingState = null,
)

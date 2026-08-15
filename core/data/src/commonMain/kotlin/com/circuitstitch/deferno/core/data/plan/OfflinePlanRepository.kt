package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.item.CachedItem
import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.item.asKindRow
import com.circuitstitch.deferno.core.data.item.toItem
import com.circuitstitch.deferno.core.model.PlanRow
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.KindRow
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate

/**
 * The offline-first [PlanRepository] (ADR-0001, #22, #385).
 *
 * **Reconcile ([refreshPlan]).** `/items/plan` is an ordered full snapshot for a day
 * (CONTRACT-NOTES -> Items), so a refresh pulls the ordered kind-tagged refs and the local store does
 * a full per-day replace (delete the day, re-insert the fresh ordered set, atomically). An
 * [RemoteSnapshot.Unavailable] pull skips the replace, leaving the cached plan intact; an
 * [RemoteSnapshot.Available] (possibly empty) one replaces — an empty day clears the ordering.
 *
 * **Resolve ([observePlan]).** The plan store holds only the ordering; the items themselves live in the
 * item cache and are reconciled independently by the `/items` cold sync. So the repository [combine]s
 * the ordered refs with the cache's live rows and resolves each ref to its cached item *in plan order*.
 *
 * **The ref's own kind is no longer consulted, and that closes defect 2 in #385 at the root.** That
 * resolve joined against the Task cache alone, so a Habit or Chore the server had seeded into the day
 * matched nothing and was dropped with no diagnostic — a plan of two recurring rows rendered as an empty
 * screen. #385 fixed it by tagging each ref with its kind and dispatching to the one store that could
 * hold it, which left two ways to lose a row: an unrecognised kind token had no store to ask, and a
 * mis-tagged ref asked the wrong one. Since #422 there is one cache keyed by id, and the row itself says
 * which kind it is. Neither failure is expressible.
 *
 * A ref that resolves to nothing is still skipped: the id is real but its row has not been pulled yet —
 * a brand-new plan entry the `/items` refresh has not caught up with — and skipping lets the rest of the
 * day render rather than stalling on it.
 *
 * **The projection is per planned row; the indexing is still per account.** The index holds cached rows
 * as the store emits them and nothing is projected until a ref resolves, which matters because this is a
 * *live* join: it re-emits on every write to the cache, and the app shell's `treeDecorations` folds it
 * into the Item tree's per-row decorations as well, so a single edit re-runs this whole lambda.
 *
 * That is not asymptotically cheap and nothing arranged inside this lambda could make it so: the
 * `associateBy` still walks every active row on every emission, because "every active row" is the shape
 * the store hands over. Answering a day's worth of ids without touching the rest of the account would
 * take a narrower query on the store itself, and is not attempted here.
 */
class OfflinePlanRepository(
    private val planStore: PlanLocalStore,
    private val remoteSource: PlanRemoteSource,
    private val items: ItemLocalStore,
    private val recipe: KindRecipe = ParityRecipe,
) : PlanRepository {

    override fun observePlan(date: LocalDate, tz: String): Flow<List<PlanRow>> =
        combine(planStore.observePlan(date), items.observeActive()) { refs, rows ->
            val byId = rows.associateBy { it.id }
            refs.mapNotNull { ref -> byId[ref.id]?.toPlanRow() }
        }

    /**
     * One resolved row. The Task arm is the only one that hands a [PlanRow] a non-null `task`, which
     * makes that type's "present exactly for a Task row" invariant structural here rather than a runtime
     * guard on the way out. The concrete row is what the four shipped Task-only affordances read — the
     * suggestion and its choice card, the deadline subline, the attention footer — and the three
     * recurring arms leave it null, which is the honest answer for them and not a missing value.
     */
    private fun CachedItem.toPlanRow(): PlanRow = when (val row = asKindRow(recipe)) {
        is KindRow.OfTask -> PlanRow(item = row.task.toItem(), task = row.task)
        is KindRow.OfHabit -> PlanRow(item = row.habit.toItem())
        is KindRow.OfChore -> PlanRow(item = row.chore.toItem())
        is KindRow.OfEvent -> PlanRow(item = row.event.toItem())
    }

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        val ordered = when (val result = remoteSource.fetchPlan(date, tz)) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        planStore.replacePlan(date, tz, ordered)
    }
}

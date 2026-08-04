package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.item.toItem
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanRow
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
 * **Resolve ([observePlan]).** The plan store holds only the ordering; the items themselves live in
 * the four per-kind caches and are reconciled independently by the `/items` cold sync. So the
 * repository [combine]s the ordered refs with all four stores' live rows and resolves each ref to its
 * cached item *in plan order*.
 *
 * That "all four" is the whole of defect 2 in #385. This resolve used to join against the Task cache
 * alone, so a Habit or Chore the server had seeded into the day matched nothing and was dropped with
 * no diagnostic — a plan of two recurring rows rendered as an empty screen. Each ref now carries its
 * kind, and an exhaustive `when` over that kind looks it up in the one store that could hold it.
 *
 * A ref that still resolves to nothing is skipped, as before: the id is real but its row has not been
 * pulled yet (a brand-new plan entry the `/items` refresh has not caught up with), and skipping lets
 * the rest of the day render rather than stalling on it. A ref whose *kind* is unknown is skipped for
 * a different reason — the token did not decode, so there is no store to ask, and guessing Task is
 * what caused this bug in the first place.
 *
 * **The `Item` projection is per planned row; the indexing is still per account.** Each store is
 * indexed by id as the concrete type it already emits, and a ref's kind picks exactly one of those
 * indexes — so `toItem()` runs once per planned row that resolves, and a Task is indexed once rather
 * than twice. That is worth being deliberate about, because this is a *live* join over five stores: it
 * re-emits on every write to any one of them, and the app shell's `treeDecorations` folds it into the
 * Item tree's per-row decorations as well — so a single edit to a single Task re-runs this whole
 * lambda. Building a cross-kind `Item` for every cached row on each of those re-runs was pure waste.
 *
 * Removing that waste did **not** make the re-run cheap in the size of the account, though, and nothing
 * arranged inside this lambda could: the four `associateBy` calls still walk every active Task, Habit,
 * Chore and Event on every emission, because "every active row" is the shape the stores hand over. The
 * saving is per cached row, not asymptotic — one hash insert now, where before there was also an object
 * allocation (and, for a Task, a second insert into a second index). Answering a day's worth of ids
 * without touching the rest of the account would take a narrower query on the stores themselves, and is
 * not attempted here.
 */
class OfflinePlanRepository(
    private val planStore: PlanLocalStore,
    private val remoteSource: PlanRemoteSource,
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
) : PlanRepository {

    override fun observePlan(date: LocalDate, tz: String): Flow<List<PlanRow>> =
        combine(
            planStore.observePlan(date),
            taskStore.observeActive(),
            habitStore.observeActive(),
            choreStore.observeActive(),
            eventStore.observeActive(),
        ) { refs, tasks, habits, chores, events ->
            // One id index per store, each holding the concrete type its store already emits —
            // nothing is projected to `Item` here. The kind dispatch below reaches into exactly one
            // index, so `toItem()` runs once per *planned* row rather than once per row in the
            // account, and a Task is indexed once (as a Task) rather than twice (as a Task and again
            // as an `Item`).
            val tasksById = tasks.associateBy { it.id.value }
            val habitsById = habits.associateBy { it.id.value }
            val choresById = chores.associateBy { it.id.value }
            val eventsById = events.associateBy { it.id.value }
            // Two distinct skips live in here, for the two different reasons the class KDoc gives:
            // an unknown kind has no store to ask, and a known kind whose id is not cached yet has
            // nothing to show. `mapNotNull` drops either and the rest of the day still renders, in
            // plan order.
            refs.mapNotNull { ref ->
                when (ref.kind) {
                    // A NAMED arm, not a fallthrough. `kind` is nullable because it is a server
                    // token this client may not recognise (see the KDoc on
                    // [com.circuitstitch.deferno.core.model.PlanItemRef]), and an exhaustive `when`
                    // makes "this build does not know what the server sent" a case the compiler
                    // forces us to answer rather than one an early return could quietly absorb. The
                    // answer is to skip: coercing an unknown token to Task is precisely the #385
                    // defect — it is how a Habit came to be looked up in the Task cache and vanish
                    // from the day with no diagnostic.
                    null -> null
                    // The only arm of this dispatch that hands a [PlanRow] a non-null `task`, which
                    // makes that type's "present exactly for a Task row" invariant structural in the
                    // production resolve — held by the shape of the `when`, not by a runtime guard on
                    // the way out. (The demo/fake repositories build their own Task-only rows; this is
                    // the only place the offline resolve does.) The
                    // concrete row is what the four shipped Task-only affordances read (the ✦
                    // suggestion and its choice card, the deadline subline, the attention footer);
                    // the three recurring arms below leave it null, which is the honest answer for
                    // them, not a missing value.
                    ItemKind.Task -> tasksById[ref.id]?.let { PlanRow(item = it.toItem(), task = it) }
                    ItemKind.Habit -> habitsById[ref.id]?.let { PlanRow(item = it.toItem()) }
                    ItemKind.Chore -> choresById[ref.id]?.let { PlanRow(item = it.toItem()) }
                    ItemKind.Event -> eventsById[ref.id]?.let { PlanRow(item = it.toItem()) }
                }
            }
        }

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        val ordered = when (val result = remoteSource.fetchPlan(date, tz)) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        planStore.replacePlan(date, tz, ordered)
    }
}

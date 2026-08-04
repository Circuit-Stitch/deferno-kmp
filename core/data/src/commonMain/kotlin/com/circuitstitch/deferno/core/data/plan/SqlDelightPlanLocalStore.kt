package com.circuitstitch.deferno.core.data.plan

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.data.calendar.toItemKindOrNull
import com.circuitstitch.deferno.core.database.sql.DailyPlanEntry
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [PlanLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #22, #385). It maps
 * the `dailyPlanEntry` rows (#21) to/from ordered [PlanItemRef] lists; the query already orders by
 * `position`, and [replacePlan] runs the per-day delete-then-reinsert inside a single
 * `db.transaction { }` so the day reconciles atomically and the observed list re-emits once.
 *
 * The observe [dispatcher] is injected (default [Dispatchers.Default]) so a test can run the Flow on
 * its own scheduler. Plan date <-> `plan_date` TEXT via `LocalDate.toString()`/`parse` (ISO `yyyy-MM-dd`).
 */
class SqlDelightPlanLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PlanLocalStore {

    private val queries get() = db.dailyPlanEntryQueries

    override fun observePlan(date: LocalDate): Flow<List<PlanItemRef>> =
        queries.selectPlan(date.toString())
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toRef() } }

    override suspend fun currentPlan(date: LocalDate): List<PlanItemRef> =
        queries.selectPlan(date.toString()).executeAsList().map { it.toRef() }

    override suspend fun replacePlan(date: LocalDate, tz: String, refs: List<PlanItemRef>) {
        val planDate = date.toString()
        db.transaction {
            queries.deletePlan(planDate)
            refs.forEachIndexed { index, ref ->
                queries.insertEntry(planDate, index.toLong(), ref.id, ref.kind?.name, tz)
            }
        }
    }

    override suspend fun rekeyItem(from: String, to: String) {
        // rekeyItem: SET item_id = ? WHERE item_id = ?  → (new, old)
        queries.rekeyItem(to, from)
    }
}

/**
 * `kind` decodes through the shared [toItemKindOrNull], so an unrecognised token stays `null` rather
 * than being coerced. That `null` is load-bearing: resolving an unknown kind as a Task is precisely
 * how a Habit came to be looked up in the Task cache and vanish from the plan (#385), so the read
 * surfaces the row as unresolvable instead of mis-resolving it.
 */
private fun DailyPlanEntry.toRef() = PlanItemRef(id = item_id, kind = kind?.toItemKindOrNull())

package com.circuitstitch.deferno.core.data.plan

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [PlanLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #22). It maps the
 * `dailyPlanEntry` rows (#21) to/from ordered [TaskId] lists; the query already orders by
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

    override fun observePlan(date: LocalDate, tz: String): Flow<List<TaskId>> =
        queries.selectPlan(date.toString(), tz)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { TaskId(it.task_id) } }

    override suspend fun replacePlan(date: LocalDate, tz: String, taskIds: List<TaskId>) {
        val planDate = date.toString()
        db.transaction {
            queries.deletePlan(planDate, tz)
            taskIds.forEachIndexed { index, id ->
                queries.insertEntry(planDate, tz, index.toLong(), id.value)
            }
        }
    }
}

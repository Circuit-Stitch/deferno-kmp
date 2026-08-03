package com.circuitstitch.deferno.core.data.calendar

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.CalendarItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [CalendarLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #74). Thin
 * SQL<->domain plumbing (via `CalendarEntityMapping.kt`); reads are observed via
 * `Query.asFlow().mapToList(...)` (ADR-0001 observe-via-Flow-only) so a window refresh re-emits the
 * grid/agenda with no manual refresh.
 *
 * Every read is a single-table query: the row's recurring `kind` is a column (#380), so there is no
 * Kotlin-side join to keep the agenda and a separate index in step. The markers flow never materialises
 * a row at all — it is a pure per-day count.
 */
class SqlDelightCalendarLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CalendarLocalStore {

    private val calQ get() = db.calendarItemEntityQueries

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>> =
        calQ.selectInRange(from.toString(), to.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeByDate(date: LocalDate): Flow<List<CalendarItem>> =
        calQ.selectByDate(date.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        calQ.selectMarkersInRange(from.toString(), to.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.associate { LocalDate.parse(it.item_date) to it.count.toInt() } }

    override suspend fun get(id: String): CalendarItem? =
        calQ.selectById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(item: CalendarItem) {
        calQ.insertOrReplace(item.toEntity())
    }

    override suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>) {
        calQ.transaction {
            calQ.deleteInRange(from.toString(), to.toString())
            items.forEach { item -> calQ.insertOrReplace(item.toEntity()) }
        }
    }
}

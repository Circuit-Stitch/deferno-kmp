package com.circuitstitch.deferno.core.data.calendar

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * The production [CalendarLocalStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #74). Thin
 * SQL<->domain plumbing (via `CalendarEntityMapping.kt`); reads are observed via
 * `Query.asFlow().mapToList(...)` (ADR-0001 observe-via-Flow-only) so a window refresh re-emits the
 * grid/agenda with no manual refresh.
 *
 * **Kind resolution** is a Kotlin-side join: the agenda/window flows [combine] the calendar rows with
 * the `seriesKindEntity` index flow, so each firing's recurring kind resolves from the series id (and
 * re-resolves if the index is reseeded). The markers flow needs no kind — it is a pure per-day count.
 */
class SqlDelightCalendarLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CalendarLocalStore {

    private val calQ get() = db.calendarItemEntityQueries
    private val kindQ get() = db.seriesKindEntityQueries

    private fun seriesKindFlow(): Flow<Map<String, ItemKind>> =
        kindQ.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            rows.mapNotNull { row -> row.kind.toItemKindOrNull()?.let { row.series_id to it } }.toMap()
        }

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>> =
        combine(
            calQ.selectInRange(from.toString(), to.toString()).asFlow().mapToList(dispatcher),
            seriesKindFlow(),
        ) { rows, index -> rows.map { it.toDomain(index[it.series_id]) } }

    override fun observeByDate(date: LocalDate): Flow<List<CalendarItem>> =
        combine(
            calQ.selectByDate(date.toString()).asFlow().mapToList(dispatcher),
            seriesKindFlow(),
        ) { rows, index -> rows.map { it.toDomain(index[it.series_id]) } }

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        calQ.selectMarkersInRange(from.toString(), to.toString()).asFlow().mapToList(dispatcher)
            .map { rows -> rows.associate { LocalDate.parse(it.item_date) to it.count.toInt() } }

    override suspend fun get(id: String): CalendarItem? =
        calQ.selectById(id).executeAsOneOrNull()?.let { row -> row.toDomain(resolveKind(row.series_id)) }

    private fun resolveKind(seriesId: String?): ItemKind? =
        seriesId?.let { kindQ.selectBySeries(it).executeAsOneOrNull()?.kind?.toItemKindOrNull() }

    override suspend fun upsert(item: CalendarItem) {
        val e = item.toEntity()
        calQ.insertOrReplace(
            id = e.id,
            task_id = e.task_id,
            series_id = e.series_id,
            title = e.title,
            item_date = e.item_date,
            start_at = e.start_at,
            end_at = e.end_at,
            all_day = e.all_day,
            working_state = e.working_state,
            source = e.source,
            labels = e.labels,
        )
    }

    override suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>) {
        calQ.transaction {
            calQ.deleteInRange(from.toString(), to.toString())
            items.forEach { item ->
                val e = item.toEntity()
                calQ.insertOrReplace(
                    id = e.id,
                    task_id = e.task_id,
                    series_id = e.series_id,
                    title = e.title,
                    item_date = e.item_date,
                    start_at = e.start_at,
                    end_at = e.end_at,
                    all_day = e.all_day,
                    working_state = e.working_state,
                    source = e.source,
                    labels = e.labels,
                )
            }
        }
    }

    override suspend fun replaceSeriesKinds(index: Map<String, ItemKind>) {
        kindQ.transaction {
            kindQ.deleteAll()
            index.forEach { (seriesId, kind) -> kindQ.insertOrReplace(seriesId, kind.name) }
        }
    }
}

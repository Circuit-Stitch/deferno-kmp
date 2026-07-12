package com.circuitstitch.deferno.core.data.activity

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import com.circuitstitch.deferno.core.database.sql.ActivityLedgerEntry as ActivityRow

/**
 * The production [ActivityLedgerStore] over the SQLDelight [DefernoDatabase] (#260). Thin SQL ↔ domain
 * plumbing on the `activityLedgerEntry` rows, mirroring [com.circuitstitch.deferno.core.data.outbox
 * .SqlDelightOutboxStore]: instants ↔ RFC3339 strings, `path` segments ↔ a `\n`-joined TEXT, the enum
 * method/source ↔ their `.name` decoded **defensively** (an unrecognised stored token degrades rather
 * than throwing). The observe [dispatcher] is injected so a test can drive the Flow on its own scheduler.
 */
class SqlDelightActivityLedgerStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ActivityLedgerStore {

    private val queries get() = db.activityLedgerEntryQueries

    override suspend fun record(source: ActivitySource, target: String, request: OutboxRequest, before: String?, now: Instant) {
        queries.record(
            recorded_at = now.toString(),
            source = source.name,
            target = target,
            method = request.method.name,
            path = request.path.joinToString("\n"),
            body = request.body,
            before = before,
        )
    }

    override fun recent(limit: Long): Flow<List<ActivityEntry>> =
        queries.recent(limit)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun clear() {
        queries.deleteAll()
    }
}

/** Decodes a stored `activityLedgerEntry` row into the domain [ActivityEntry]. Defensive on enum columns. */
private fun ActivityRow.toDomain(): ActivityEntry = ActivityEntry(
    seq = seq,
    recordedAt = Instant.parse(recorded_at),
    source = ActivitySource.fromToken(source),
    target = target,
    method = OutboxMethod.entries.firstOrNull { it.name == method } ?: OutboxMethod.Post,
    path = if (path.isEmpty()) emptyList() else path.split("\n"),
    body = body,
    before = before,
)

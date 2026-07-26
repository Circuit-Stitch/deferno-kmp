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
 * The production [ActivityLedgerStore] over the SQLDelight [DefernoDatabase]. Thin SQL ↔ domain plumbing
 * on the `activityLedgerEntry` rows, mirroring [com.circuitstitch.deferno.core.data.outbox
 * .SqlDelightOutboxStore]: instants ↔ RFC3339 strings, `path` segments ↔ a `\n`-joined TEXT, the enum
 * method/source/kinds ↔ their tokens decoded **defensively** (an unrecognised stored token degrades rather
 * than throwing — a diagnostics screen must never be the thing that crashes). The observe [dispatcher] is
 * injected so a test can drive the Flow on its own scheduler.
 */
class SqlDelightActivityLedgerStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ActivityLedgerStore {

    private val queries get() = db.activityLedgerEntryQueries
    private val syncQueries get() = db.activitySyncStateQueries

    override suspend fun recordLocal(
        source: ActivitySource,
        target: String,
        request: OutboxRequest,
        before: String?,
        now: Instant,
        stamp: ActivityStamp?,
        actionKind: ActivityActionKind?,
    ) {
        queries.recordLocal(
            recorded_at = now.toString(),
            source = source.name,
            target = target,
            method = request.method.name,
            path = request.path.joinToString("\n"),
            body = request.body,
            before = before,
            entry_id = stamp?.entryId,
            // The optimistic row sorts by the same instant it asserted on the wire, so a local write and
            // its eventual server twin occupy the same position in the feed and the swap is invisible.
            occurred_at = (stamp?.occurredAt ?: now).toString(),
            action_kind = actionKind?.token,
            item_id = null,
        )
    }

    override suspend fun upsertRemote(entries: List<RemoteActivityEntry>) {
        if (entries.isEmpty()) return
        // One transaction per page: a partially-merged page would advance the cursor past rows that never
        // landed, and the `since` axis is gapless precisely so that cannot happen.
        db.transaction {
            for (e in entries) {
                val changedFields = e.changedFields.takeIf { it.isNotEmpty() }?.joinToString("\n")
                // Insert-if-absent, then update: the pair is the portable spelling of an upsert keyed on
                // the UNIQUE entry_id (see the .sq). The insert is a no-op when an optimistic row already
                // holds this id, and the update then overwrites only the authoritative columns — leaving
                // that row's captured body/before/target, which are richer than `detail`, untouched.
                queries.insertRemoteIfAbsent(
                    recorded_at = e.observedAt.toString(),
                    source = e.source.name,
                    // A server row has no outbox shape. Empty is the read model's "no outbox derivation
                    // available" signal, which is safe because such a row always carries an action_kind.
                    target = "",
                    method = "",
                    path = "",
                    entry_id = e.entryId,
                    occurred_at = e.occurredAt.toString(),
                    observed_at = e.observedAt.toString(),
                    action_kind = e.actionKind.token,
                    actor_kind = e.actorKind.token,
                    provider = e.provider,
                    item_id = e.itemId,
                    occurrence = e.occurrence,
                    series_id = e.seriesId,
                    changed_fields = changedFields,
                    detail = e.detail,
                )
                queries.updateRemote(
                    source = e.source.name,
                    occurred_at = e.occurredAt.toString(),
                    observed_at = e.observedAt.toString(),
                    action_kind = e.actionKind.token,
                    actor_kind = e.actorKind.token,
                    provider = e.provider,
                    item_id = e.itemId,
                    occurrence = e.occurrence,
                    series_id = e.seriesId,
                    changed_fields = changedFields,
                    detail = e.detail,
                    entry_id = e.entryId,
                )
            }
        }
    }

    override fun recent(limit: Long): Flow<List<ActivityEntry>> =
        queries.recent(limit)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun pruneOlderThan(cutoff: Instant) {
        queries.pruneOlderThan(cutoff.toString())
    }

    override suspend fun syncCursor(): String? = syncQueries.selectState().executeAsOneOrNull()?.cursor

    override suspend fun setSyncCursor(cursor: String?, now: Instant) {
        syncQueries.upsertState(cursor = cursor, synced_at = now.toString())
    }

    override suspend fun clear() {
        db.transaction {
            queries.deleteAll()
            // The cursor goes with the rows. Keeping a watermark whose entries had been deleted would make
            // the next sync resume from it and never re-fetch what it just dropped — a permanently
            // truncated feed that looks like data loss.
            syncQueries.deleteAll()
        }
    }
}

/** Decodes a stored `activityLedgerEntry` row into the domain [ActivityEntry]. Defensive on every enum column. */
private fun ActivityRow.toDomain(): ActivityEntry = ActivityEntry(
    seq = seq,
    recordedAt = Instant.parse(recorded_at),
    source = ActivitySource.fromToken(source),
    target = target,
    method = OutboxMethod.entries.firstOrNull { it.name == method } ?: OutboxMethod.Post,
    path = if (path.isEmpty()) emptyList() else path.split("\n"),
    body = body,
    before = before,
    entryId = entry_id,
    occurredAt = occurred_at?.let { runCatching { Instant.parse(it) }.getOrNull() },
    observedAt = observed_at?.let { runCatching { Instant.parse(it) }.getOrNull() },
    actionKind = action_kind?.let(ActivityActionKind::fromToken),
    actorKind = actor_kind?.let(ActivityActorKind::fromToken),
    provider = provider,
    serverItemId = item_id,
    occurrence = occurrence,
    seriesId = series_id,
    changedFields = changed_fields?.split("\n").orEmpty(),
    detail = detail,
)

package com.circuitstitch.deferno.core.data.activity

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
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
        change: LocalActivityChange,
        at: Instant,
        stamp: ActivityStamp?,
        actionKind: ActivityActionKind?,
    ) {
        val instant = at.toString()
        queries.recordLocal(
            recorded_at = instant,
            // Always Mobile: this is the local write path by definition, and every other surface's rows
            // arrive through `upsertRemote`.
            source = ActivitySource.Mobile.name,
            target = change.target,
            method = change.method.name,
            path = change.path.joinToString("\n"),
            body = change.body,
            before = change.before,
            entry_id = stamp?.entryId,
            // The same instant, not a second derivation of it: a local write's apply time IS the actor's
            // wall-clock, and [stamp] asserted that reading on the wire. So the optimistic row sorts where
            // it displays, and lands where its eventual server twin will — the swap is invisible.
            occurred_at = instant,
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
                //
                // The insert carries only the NOT NULL skeleton: the update below sets every authoritative
                // column on both branches, so binding the same values twice would buy nothing and give the
                // two argument lists a way to drift apart.
                queries.insertRemoteIfAbsent(
                    recorded_at = e.observedAt.toString(),
                    source = e.source.name,
                    entry_id = e.entryId,
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
private fun ActivityRow.toDomain(): ActivityEntry {
    val appliedAt = Instant.parse(recorded_at)
    return ActivityEntry(
        seq = seq,
        recordedAt = appliedAt,
        source = ActivitySource.fromToken(source),
        target = target,
        method = OutboxMethod.entries.firstOrNull { it.name == method } ?: OutboxMethod.Post,
        path = if (path.isEmpty()) emptyList() else path.split("\n"),
        body = body,
        before = before,
        entryId = entry_id,
        // Migration 16 back-filled this column and every writer sets it, so the fallback survives only as
        // a decode guard: an unparseable stored string must degrade to the apply time, not sink the row to
        // the epoch (or throw — a diagnostics screen must never be the thing that crashes).
        occurredAt = occurred_at?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: appliedAt,
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
}

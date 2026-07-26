package com.circuitstitch.deferno.core.data.activity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.SqlDelightOutboxStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline-first activity ledger (#260): proves the single choke-point ([LedgerRecordingOutboxStore])
 * records EVERY write into the durable ledger at apply-time, reverse-chronologically and tagged local,
 * over a real in-memory `DefernoDatabase` (ADR-0006 JVM-fast path), plus the read-time summary/deep-link
 * derivations for each target shape.
 *
 * Since #364 the table is an optimistic *cache* of the server's ledger rather than the source of truth, so
 * this also covers the SQL that makes that work: the `entry_id` merge (server wins, local capture kept),
 * the `occurred_at` sort and prune (including migration 16's back-fill of it), and the delta cursor's
 * lifecycle.
 */
class ActivityLedgerTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val t1 = Instant.parse("2026-06-21T12:00:01Z")
    private val t2 = Instant.parse("2026-06-21T12:00:02Z")
    private val t3 = Instant.parse("2026-06-21T12:00:03Z")
    private val t4 = Instant.parse("2026-06-21T12:00:04Z")

    private fun newDb() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    /** One authoritative server entry; each test overrides only the columns it actually asserts on. */
    private fun remote(
        entryId: String,
        itemId: String = "a",
        actionKind: ActivityActionKind = ActivityActionKind.Updated,
        actorKind: ActivityActorKind = ActivityActorKind.Human,
        source: ActivitySource = ActivitySource.Mobile,
        occurredAt: Instant = t0,
        observedAt: Instant = t2,
        changedFields: List<String> = emptyList(),
        detail: String? = null,
    ) = RemoteActivityEntry(
        entryId = entryId,
        itemId = itemId,
        actionKind = actionKind,
        actorKind = actorKind,
        source = source,
        occurredAt = occurredAt,
        observedAt = observedAt,
        changedFields = changedFields,
        detail = detail,
    )

    /** A local write that carried the client-minted `activity` stamp — the row a reconcile can merge onto. */
    private suspend fun SqlDelightActivityLedgerStore.recordStamped(
        target: String,
        stamp: ActivityStamp,
        now: Instant,
        body: String = "{}",
        before: String? = null,
    ) = recordLocal(
        source = ActivitySource.Mobile,
        target = target,
        request = OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), body),
        before = before,
        now = now,
        stamp = stamp,
    )

    /**
     * A local write on a route that cannot carry the `activity` sibling (`PATCH auth/me/settings`,
     * `DELETE tasks/{id}`): no stamp, so no `entry_id` to merge on — but `occurred_at` is still written,
     * because every insert path stamps it. The only rows without one predate migration 16, which
     * back-fills them (see [migration16BackfillsOccurredAtSoAnUpgradedRowKeepsItsPlaceInTheFeed]).
     */
    private suspend fun SqlDelightActivityLedgerStore.recordUnstamped(target: String, now: Instant) =
        recordLocal(
            source = ActivitySource.Mobile,
            target = target,
            request = OutboxRequest(OutboxMethod.Patch, listOf("tasks"), null),
            before = null,
            now = now,
            stamp = null,
        )

    @Test
    fun decoratorRecordsEveryEnqueueReverseChronAsLocal() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        val outbox = LedgerRecordingOutboxStore(SqlDelightOutboxStore(db), ledger)

        // The first two declare the `activity` stamp as their real routes do, so each writes a distinct
        // `entry_id` through the unique index; the bodiless delete cannot, and records with none.
        outbox.enqueue(
            "task:a",
            OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"x"}""", acceptsActivityStamp = true),
            t0,
        )
        outbox.enqueue(
            "create:Task:b",
            OutboxRequest(OutboxMethod.Post, listOf("tasks"), """{"id":"b"}""", acceptsActivityStamp = true),
            t1,
        )
        outbox.enqueue("task:a", OutboxRequest(OutboxMethod.Delete, listOf("tasks", "a")), t2)

        // The durable journal mirrors all three, newest first, every one tagged the local source.
        val feed = ledger.recent().first()
        assertEquals(3, feed.size)
        assertEquals(listOf(t2, t1, t0), feed.map { it.recordedAt })
        assertTrue(feed.all { it.source == ActivitySource.Mobile })
        assertEquals(
            listOf(
                ActivitySummary(ActivityVerb.DeletedTask),
                ActivitySummary(ActivityVerb.Created, "task"),
                ActivitySummary(ActivityVerb.UpdatedTask),
            ),
            feed.map { it.summaryInfo() },
        )

        // The outbox queue itself is unaffected — the decorator delegated the real enqueue.
        assertEquals(3L, outbox.count())
    }

    @Test
    fun recordsAndReadsBackTheOldNewDiffPayload() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        val outbox = LedgerRecordingOutboxStore(SqlDelightOutboxStore(db), ledger)

        outbox.enqueue(
            "task:a",
            OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"new"}""", acceptsActivityStamp = true),
            t0,
            before = """{"title":"old"}""",
        )

        // The new value rides the request body; the old value is the ledger's captured before-image.
        val entry = ledger.recent().first().single()
        assertEquals("""{"title":"new"}""", entry.body)
        assertEquals("""{"title":"old"}""", entry.before)
        // …and they zip into a typed old->new field diff.
        val change = entry.changes().single()
        assertEquals(ActivityFieldValue.Present("old"), change.before)
        assertEquals(ActivityFieldValue.Present("new"), change.after)
    }

    @Test
    fun clearEmptiesLedger() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        ledger.recordLocal(ActivitySource.Mobile, "task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), "{}"), before = null, now = t0)
        assertEquals(1, ledger.recent().first().size)
        ledger.clear()
        assertTrue(ledger.recent().first().isEmpty())
    }

    @Test
    fun serverEntryReplacesItsOptimisticTwinInPlaceKeepingTheRicherLocalCapture() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        ledger.recordStamped(
            "task:a",
            stamp = ActivityStamp("entry-1", t0),
            now = t0,
            body = """{"title":"new"}""",
            before = """{"title":"old"}""",
        )

        ledger.upsertRemote(
            listOf(
                remote(
                    entryId = "entry-1",
                    // The server echoes the client-asserted wall-clock and stamps its own receive time.
                    occurredAt = t0,
                    observedAt = t2,
                    changedFields = listOf("title"),
                    detail = """{"item_kind":"task","fields":{"title":{"old":"old","new":"new"}}}""",
                ),
            ),
        )

        // ONE row, not two. Without the merge on the UNIQUE entry_id the same edit would appear twice in
        // the feed the moment the reconcile caught up — the exact double-count the client-minted id exists
        // to prevent.
        val entry = ledger.recent().first().single()
        assertEquals("entry-1", entry.entryId)

        // The authoritative half is now the server's.
        assertEquals(ActivityActionKind.Updated, entry.actionKind)
        assertEquals(ActivityActorKind.Human, entry.actorKind)
        assertEquals(t2, entry.observedAt)
        assertTrue(entry.isAcknowledged)
        assertEquals(listOf("title"), entry.changedFields)

        // …and the outbox-derived half survives untouched. `body`/`before` hold the values this device
        // actually sent, which are richer than the server's whitelisted `detail`, so letting the update
        // overwrite them would LOSE diff fidelity on precisely the rows the client knows most about.
        assertEquals("task:a", entry.target)
        assertEquals("""{"title":"new"}""", entry.body)
        assertEquals("""{"title":"old"}""", entry.before)
        val change = entry.changes().single()
        assertEquals(ActivityFieldValue.Present("old"), change.before)
        assertEquals(ActivityFieldValue.Present("new"), change.after)

        // The row also keeps its position in the feed, so the optimistic->authoritative swap is invisible.
        assertEquals(t0, entry.occurredAt)
    }

    @Test
    fun serverEntryWithAnUnseenEntryIdInsertsANewRow() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        ledger.recordStamped("task:a", stamp = ActivityStamp("entry-1", t0), now = t0)

        ledger.upsertRemote(
            listOf(
                remote(
                    entryId = "entry-2",
                    itemId = "b",
                    actionKind = ActivityActionKind.Created,
                    source = ActivitySource.Website,
                    occurredAt = t1,
                    observedAt = t2,
                    detail = """{"item_kind":"chore"}""",
                ),
            ),
        )

        // Grow-only: a change made on another surface is history this device has never seen, so the merge
        // must append it rather than only ever overwriting rows it already had.
        val feed = ledger.recent().first()
        assertEquals(2, feed.size)

        val fromWeb = feed.single { it.entryId == "entry-2" }
        // A server row has no outbox shape at all. Empty target/path is the read model's "nothing to derive
        // from" signal — safe only because such a row always names its own verb, which it does here.
        assertEquals("", fromWeb.target)
        assertEquals(emptyList<String>(), fromWeb.path)
        assertNull(fromWeb.body)
        assertEquals(ActivitySource.Website, fromWeb.source)
        assertEquals("b", fromWeb.itemId())
        assertEquals(ActivitySummary(ActivityVerb.Created, "chore"), fromWeb.summaryInfo())
    }

    @Test
    fun mergingTheSamePageTwiceLeavesTheSameRows() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        val page = listOf(
            remote("entry-1", occurredAt = t0, observedAt = t1),
            remote("entry-2", itemId = "b", occurredAt = t1, observedAt = t2),
        )

        ledger.upsertRemote(page)
        val afterFirstMerge = ledger.recent().first()
        ledger.upsertRemote(page)
        val afterSecondMerge = ledger.recent().first()

        // Replaying a page is routine — a retried request, or a cursor that overlaps at the boundary — so a
        // non-idempotent merge would grow the feed by a full page on every sync.
        assertEquals(2, afterSecondMerge.size)
        // Same rows, not fresh ones: `seq` is the feed's stable list key, so a delete-and-reinsert merge
        // would also scramble list state (and re-run item animations) on every tick.
        assertEquals(afterFirstMerge.map { it.seq }, afterSecondMerge.map { it.seq })
    }

    @Test
    fun rowsWithNoEntryIdCoexistRatherThanCollidingOnTheUniqueIndex() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        // A route that cannot carry the `activity` sibling records with no stamp, so it mints no id either.
        ledger.recordUnstamped("task:unstamped-1", now = t0)
        ledger.recordUnstamped("task:unstamped-2", now = t1)
        ledger.recordLocal(
            ActivitySource.Mobile,
            "settings",
            OutboxRequest(OutboxMethod.Patch, listOf("auth", "me", "settings"), "{}"),
            before = null,
            now = t2,
            stamp = null,
        )

        // SQLite treats NULLs as DISTINCT in a UNIQUE index. That is the whole reason migration 16 can add
        // `CREATE UNIQUE INDEX … (entry_id)` to a table already full of pre-#364 rows without throwing, and
        // the reason an un-stampable write is merely superseded rather than colliding with the last one.
        val feed = ledger.recent().first()
        assertEquals(3, feed.size)
        assertTrue(feed.all { it.entryId == null })
    }

    @Test
    fun feedSortsByWhenTheChangeHappenedNotByWhenTheRowWasInserted() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        // Seeded the way it actually happens: local rows land first, then a reconcile appends entries that
        // HAPPENED EARLIER (another device flushing an offline backlog, a webhook, a web edit). Insertion
        // order and occurred_at deliberately disagree here — a dataset where they agree cannot catch an
        // `ORDER BY seq` regression, because both spellings would pass it.
        ledger.recordStamped("task:a", stamp = ActivityStamp("entry-newest", t2), now = t2)
        ledger.recordUnstamped("task:unstamped", now = t3)
        ledger.upsertRemote(
            listOf(
                remote("entry-oldest", occurredAt = t0, observedAt = t4),
                remote("entry-middle", occurredAt = t1, observedAt = t4),
            ),
        )

        val feed = ledger.recent().first()
        // Sorted on occurred_at DESC: the two last-inserted rows sort BELOW, and the unstamped row — which
        // has no entry_id to merge on but still carries its apply time — takes its place among them.
        assertEquals(listOf(t3, t2, t1, t0), feed.map { it.occurredAt })
        assertEquals(listOf(null, "entry-newest", "entry-middle", "entry-oldest"), feed.map { it.entryId })
        // Guards the guard: if a later edit "tidies" the seeding so insertion order and occurred_at line
        // up again, this test would silently stop discriminating between the two ORDER BY clauses.
        val seqs = feed.map { it.seq }
        assertTrue(seqs != seqs.sortedDescending(), "seeded order must disagree with seq order, was $seqs")
    }

    @Test
    fun pruneOlderThanCutsOnTheOccurredAtAxisNotTheApplyTime() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        ledger.recordUnstamped("task:unstamped-old", now = t0)
        ledger.recordUnstamped("task:unstamped-kept", now = t4)
        ledger.recordStamped("task:a", stamp = ActivityStamp("entry-kept", t3), now = t3)
        // Backdated: the server observed it at t4 (so recorded_at is fresh) but the actor did it at t0.
        ledger.upsertRemote(listOf(remote("entry-backdated", occurredAt = t0, observedAt = t4)))

        ledger.pruneOlderThan(t2)

        // Retention trims the same axis the feed sorts on, so what survives is exactly the visible tail. A
        // prune on recorded_at instead would keep the backdated row — fresh recorded_at, ancient position —
        // i.e. hold on to precisely the row the user can no longer see.
        val feed = ledger.recent().first()
        assertEquals(listOf(t4, t3), feed.map { it.occurredAt })
        assertEquals(listOf(null, "entry-kept"), feed.map { it.entryId })
    }

    /**
     * A device that upgraded across #364. Its rows predate every column migration 16 adds, so `occurred_at`
     * would be NULL on all of them — and with the COALESCE gone that is not cosmetic: SQLite orders NULL
     * smallest, so a DESC feed files the row BELOW the user's entire history, and `NULL < ?` is never true,
     * so no prune ever reaches it. The back-fill is what stops that being an immortal invisible row.
     *
     * The v16 shape is spelled out here because there is nothing to load it from: the generated `Schema`
     * can only `create()` the CURRENT version, and `databases/16.db` is a build input, not a test resource.
     * Only this one table is needed — migration 16 touches nothing else.
     */
    @Test
    fun migration16BackfillsOccurredAtSoAnUpgradedRowKeepsItsPlaceInTheFeed() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """
            CREATE TABLE activityLedgerEntry (
                seq         INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_at TEXT NOT NULL,
                source      TEXT NOT NULL,
                target      TEXT NOT NULL,
                method      TEXT NOT NULL,
                path        TEXT NOT NULL,
                body        TEXT,
                before      TEXT
            );
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            null,
            "INSERT INTO activityLedgerEntry(recorded_at, source, target, method, path) " +
                "VALUES ('$t1', 'Mobile', 'task:upgraded', 'Patch', 'tasks');",
            parameters = 0,
        )

        DefernoDatabase.Schema.migrate(driver, 16L, 17L)
        val ledger = SqlDelightActivityLedgerStore(DefernoDatabase(driver), Dispatchers.Unconfined)

        // The upgraded row now sorts and displays at its apply time…
        assertEquals(listOf(t1), ledger.recent().first().map { it.occurredAt })
        // …and — the assertion that actually discriminates — retention can see it at all. The decoder's
        // `?: appliedAt` guard masks a stored NULL on READ, so the line above passes either way; only the
        // prune can tell a back-filled column from a NULL one. Deleting it disarms this whole test.
        ledger.pruneOlderThan(t2)
        assertTrue(ledger.recent().first().isEmpty())
    }

    /** The decoder's last line of defence: a stored instant that will not parse must not sink the row. */
    @Test
    fun anUnparseableStoredOccurredAtDecodesToTheApplyTimeRatherThanTheEpoch() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        db.activityLedgerEntryQueries.recordLocal(
            recorded_at = t1.toString(),
            source = ActivitySource.Mobile.name,
            target = "task:corrupt",
            method = OutboxMethod.Patch.name,
            path = "tasks",
            body = null,
            before = null,
            entry_id = null,
            occurred_at = "yesterday afternoon",
            action_kind = null,
            item_id = null,
        )

        assertEquals(t1, ledger.recent().first().single().occurredAt)
    }

    @Test
    fun syncCursorRoundTripsAndClearWipesRowsAndCursorTogether() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)

        // Null before the first sync is the bootstrap signal: ActivitySync then starts from a bare RFC-3339
        // timestamp instead of an opaque token. A non-null default would skip the bootstrap window entirely.
        assertNull(ledger.syncCursor())

        ledger.setSyncCursor("cursor-1", t0)
        assertEquals("cursor-1", ledger.syncCursor())
        // The state row is a singleton pinned to id 0, so advancing REPLACES the watermark. If it appended,
        // `selectState` would start returning whichever row SQLite felt like — including a stale one.
        ledger.setSyncCursor("cursor-2", t1)
        assertEquals("cursor-2", ledger.syncCursor())

        ledger.upsertRemote(listOf(remote("entry-1")))
        ledger.clear()

        // Rows and cursor go together. Keeping a watermark whose rows were just deleted would make the next
        // sync resume PAST them and never re-fetch — a feed permanently truncated at sign-out, which reads
        // to the user as data loss.
        assertTrue(ledger.recent().first().isEmpty())
        assertNull(ledger.syncCursor())
    }

    @Test
    fun summaryAndItemIdCoverEveryTargetShape() {
        fun entry(target: String, method: OutboxMethod, path: List<String> = emptyList()) =
            ActivityEntry(seq = 1, recordedAt = t0, source = ActivitySource.Mobile, target = target, method = method, path = path)

        assertEquals(ActivitySummary(ActivityVerb.UpdatedTask), entry("task:abc", OutboxMethod.Patch).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.DeletedTask), entry("task:abc", OutboxMethod.Delete).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.Created, "habit"), entry("create:Habit:h1", OutboxMethod.Post).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.MovedItem), entry("item:i1", OutboxMethod.Patch).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.UpdatedPlan), entry("plan:2026-06-21:UTC", OutboxMethod.Post).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.ChangedSettings), entry("settings", OutboxMethod.Patch).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.UpdatedOccurrence, "event"), entry("occurrence:Event:s1:2026-06-21", OutboxMethod.Patch).summaryInfo())
        // #364: clear is a POST soft-delete (`…/occurrences/{date}/clear`), so the METHOD no longer
        // distinguishes it from a mark — the path's trailing segment does.
        assertEquals(
            ActivitySummary(ActivityVerb.ClearedOccurrence, "event"),
            entry(
                "occurrence:Event:s1:2026-06-21",
                OutboxMethod.Post,
                listOf("events", "s1", "occurrences", "2026-06-21", "clear"),
            ).summaryInfo(),
        )
        assertEquals(ActivitySummary(ActivityVerb.UpdatedItem), entry("weird:thing", OutboxMethod.Patch).summaryInfo())
        // Comment writes (ADR-0043): post/edit (comment-create: / comment:) and delete all read "Commented".
        assertEquals(ActivitySummary(ActivityVerb.Commented), entry("comment-create:t1:c1", OutboxMethod.Post).summaryInfo())
        assertEquals(ActivitySummary(ActivityVerb.Commented), entry("comment:c1", OutboxMethod.Patch).summaryInfo())
        assertEquals(
            ActivitySummary(ActivityVerb.Commented),
            entry("comment:c1", OutboxMethod.Post, listOf("comments", "c1", "delete")).summaryInfo(),
        )

        assertEquals("abc", entry("task:abc", OutboxMethod.Patch).itemId())
        assertEquals("i1", entry("item:i1", OutboxMethod.Patch).itemId())
        assertEquals("h1", entry("create:Habit:h1", OutboxMethod.Post).itemId())
        assertNull(entry("plan:2026-06-21:UTC", OutboxMethod.Post).itemId())
        assertNull(entry("settings", OutboxMethod.Patch).itemId())
        // Comment ledger rows are non-deep-linking (the edit/delete target carries the comment id, not the task).
        assertNull(entry("comment:c1", OutboxMethod.Patch).itemId())
        assertNull(entry("comment-create:t1:c1", OutboxMethod.Post).itemId())
    }

    @Test
    fun unknownSourceTokenDegradesRatherThanThrows() {
        assertEquals(ActivitySource.Mobile, ActivitySource.fromToken("Mobile"))
        assertEquals(ActivitySource.Unknown, ActivitySource.fromToken("Telepathy"))
    }
}

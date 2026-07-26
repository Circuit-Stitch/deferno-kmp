package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.network.dto.ActivityEntryDto
import com.circuitstitch.deferno.core.network.dto.ActivityFeedDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `?since=` reconcile of [ActivitySync] (#364) — the client's one and only delta sync — driven
 * through hand-written fakes on the ADR-0006 JVM-fast path, with a fixed clock so every derived
 * timestamp is an exact string.
 *
 * The cases here are the ones that cost real bugs elsewhere: a watermark the client re-derives instead
 * of replaying (which reopens the observed_at/occurred_at gap the backend closed), a pager that stops on
 * a null cursor (which never stops, because the last non-empty page still returns one), an empty page
 * that overwrites the stored watermark with null (which silently re-pulls the whole window every tick),
 * and an entry dropped merely because this build doesn't recognise its verb (which under-reports a
 * forensic stream).
 */
class ActivitySyncTest {

    /** Injected everywhere, so `now - bootstrapWindow` / `now - retention` are assertable literals. */
    private val fixedNow = Instant.parse("2026-07-25T09:30:00Z")

    /** Deliberately not a timestamp: a re-derived watermark could never accidentally equal it. */
    private val opaqueCursor = "eyJvYnNlcnZlZF9hdCI6IjIwMjYtMDctMjBUMDg6MDA6MDBaIiwiaWQiOiJlLTQyIn0="

    private fun sync(
        remote: FakeActivityRemoteSource,
        ledger: RecordingActivityLedgerStore,
        pageSize: Int = 2,
        maxPages: Int = ActivitySync.DEFAULT_MAX_PAGES,
    ) = ActivitySync(
        remoteSource = remote,
        ledger = ledger,
        now = { fixedNow },
        pageSize = pageSize,
        maxPages = maxPages,
    )

    // --- the watermark: bootstrapped once, then replayed verbatim ---

    @Test
    fun aFirstSyncWithNoStoredCursorBootstrapsFromABareTimestampOneWindowBack() = runTest {
        val remote = FakeActivityRemoteSource(page(entries = listOf(entry("a")), nextSince = "c1"))
        val ledger = RecordingActivityLedgerStore(initialCursor = null)

        sync(remote, ledger).sync()

        // The endpoint accepts a bare RFC-3339 timestamp in place of an opaque token, so a fresh install
        // reaches back exactly one bootstrap window (30d) — bounded, not "everything since the account began".
        assertEquals(listOf("2026-06-25T09:30:00Z"), remote.sinceValues)
    }

    @Test
    fun aStoredCursorIsReplayedVerbatimAndNeverRederivedFromTheClock() = runTest {
        val remote = FakeActivityRemoteSource(page(entries = listOf(entry("a")), nextSince = "c1"))
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger).sync()

        // The regression this guards: a client that re-derived a timestamp of its own would skip entries
        // whose observed_at was in range but whose occurred_at fell below the page cut — routine whenever a
        // phone flushes a backlog of offline-backdated writes. Only the server's token is gapless.
        assertEquals(listOf(opaqueCursor), remote.sinceValues)
    }

    // --- paging: a full page continues, a short page stops ---

    @Test
    fun aFullPageKeepsPagingOnTheReturnedNextSinceUntilAShortPageArrives() = runTest {
        val remote = FakeActivityRemoteSource(
            page(entries = listOf(entry("a"), entry("b")), nextSince = "c1"), // full -> more may be waiting
            page(entries = listOf(entry("c"), entry("d")), nextSince = "c2"), // full -> keep going
            page(entries = listOf(entry("e")), nextSince = "c3"), // short -> caught up
        )
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2).sync()

        // Each page's `next_since` is what the next request carries — never a locally computed one.
        assertEquals(listOf(opaqueCursor, "c1", "c2"), remote.sinceValues)
        assertEquals(listOf(2, 2, 2), remote.limits) // every page asks for the configured page size
        assertEquals(listOf("a", "b", "c", "d", "e"), ledger.mergedIds)
        assertEquals("c3", ledger.storedCursor) // the whole catch-up is committed as one watermark walk
    }

    // --- the termination trap ---

    @Test
    fun aShortButNonEmptyPageTerminatesEvenThoughItStillHandsBackACursor() = runTest {
        // End-of-feed is a SHORT page, not a null cursor: the last non-empty page still returns
        // `next_since`, so a pager that stopped on `cursor == null` would page this server forever.
        val remote = FakeActivityRemoteSource(page(entries = listOf(entry("a")), nextSince = "c1"))
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2).sync()

        assertEquals(1, remote.calls.size) // the fake fails the test outright if asked for a page past the script
        // ...and the cursor it did hand back is still persisted, so the next tick resumes past these rows
        // rather than re-pulling them.
        assertEquals("c1", ledger.storedCursor)
        assertEquals(listOf("c1"), ledger.cursorWrites.map { it.first })
        assertEquals(listOf(fixedNow), ledger.cursorWrites.map { it.second })
    }

    // --- an empty page: caught up, and the watermark must survive it ---

    @Test
    fun anEmptyPageStopsTheLoopAndLeavesThePreviouslyStoredWatermarkUntouched() = runTest {
        val remote = FakeActivityRemoteSource(page(entries = emptyList(), nextSince = null))
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2).sync()

        assertEquals(1, remote.calls.size)
        // Writing the page's absent cursor through would reset the watermark to null and re-pull the whole
        // bootstrap window on every subsequent tick — a quiet ledger would never stop syncing.
        assertEquals(opaqueCursor, ledger.storedCursor)
        assertTrue(ledger.cursorWrites.isEmpty())
    }

    @Test
    fun aPassThatMergedNothingSkipsThePruneEntirely() = runTest {
        val remote = FakeActivityRemoteSource(page(entries = emptyList(), nextSince = null))
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2).sync()

        // This pass fires every five minutes for the whole session, and the reconcile is the only thing that
        // grows the ledger — so a pass that merged nothing cannot have pushed anything past the retention
        // window that was not already past it. Spending a DELETE (a write transaction, through SQLCipher on
        // Android) per tick to rediscover that is pure overhead on a surface nobody is looking at.
        assertTrue(ledger.pruneCutoffs.isEmpty())
    }

    // --- offline-first: an unavailable pull changes nothing ---

    @Test
    fun anUnavailablePullLeavesBothTheCursorAndTheMergedRowsUntouched() = runTest {
        // Unavailable covers the offline transport, a 401, and the 503 an environment with no configured
        // ledger returns. The feed is a diagnostics surface: it must never be why a sync tick reports failure,
        // so this call simply returns — if it threw, the test fails here.
        val remote = FakeActivityRemoteSource(RemoteSnapshot.Unavailable)
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger).sync()

        assertEquals(1, remote.calls.size)
        assertEquals(opaqueCursor, ledger.storedCursor) // next tick resumes from exactly the same point
        assertTrue(ledger.merges.isEmpty()) // and the cached rows keep rendering
        assertTrue(ledger.cursorWrites.isEmpty())
        assertTrue(ledger.pruneCutoffs.isEmpty()) // …including the rows retention would otherwise trim blind
    }

    // --- one pass is bounded, however much the server has ---

    @Test
    fun maxPagesBoundsASinglePassAgainstAServerThatNeverRunsDry() = runTest {
        val remote = FakeActivityRemoteSource().apply {
            // A busy org could otherwise keep one client paging indefinitely and monopolise the tick.
            endless = ActivityFeedDto(entries = listOf(entry("x"), entry("y")), nextSince = "always-more")
        }
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2, maxPages = 3).sync()

        assertEquals(3, remote.calls.size) // stopped by maxPages, not by the server
        assertEquals("always-more", ledger.storedCursor) // whatever is left is picked up next tick
        assertEquals(1, ledger.pruneCutoffs.size) // a bounded pass still trims the local window
    }

    // --- condensing a page: drop only what cannot be placed ---

    @Test
    fun anEntryWithAnUnparseableTimestampIsDroppedWhileTheRestOfThePageStillMerges() = runTest {
        val remote = FakeActivityRemoteSource(
            page(
                entries = listOf(
                    entry("good-1"),
                    entry("bad-occurred", occurredAt = "yesterday afternoon"),
                    entry("bad-observed", observedAt = ""),
                    entry("good-2"),
                ),
                nextSince = "c1",
            ),
        )
        val ledger = RecordingActivityLedgerStore()

        sync(remote, ledger, pageSize = 10).sync()

        // Both instants are load-bearing (one is the sort axis, the other the sync watermark), so a row
        // missing either cannot be placed — but one bad row must not cost the page.
        assertEquals(listOf("good-1", "good-2"), ledger.mergedIds)
    }

    @Test
    fun anUnknownActionKindIsMergedWithItsRawTokenRatherThanDropped() = runTest {
        val remote = FakeActivityRemoteSource(
            page(entries = listOf(entry("a", actionKind = "quantum_entangled")), nextSince = "c1"),
        )
        val ledger = RecordingActivityLedgerStore()

        sync(remote, ledger, pageSize = 10).sync()

        // Dropping an entry this build merely doesn't *recognise* would silently under-report a forensic
        // stream — the one thing this feed must not do. The raw token rides along for a future build.
        assertEquals(ActivityActionKind.Other("quantum_entangled"), ledger.merged.single().actionKind)
        assertEquals("quantum_entangled", ledger.merged.single().actionKind.token)
    }

    // --- pruning keeps the local window a strict subset of the server's ---

    @Test
    fun aPassPrunesTheLocalWindowAtNowMinusRetention() = runTest {
        val remote = FakeActivityRemoteSource(page(entries = listOf(entry("a")), nextSince = "c1"))
        val ledger = RecordingActivityLedgerStore(initialCursor = opaqueCursor)

        sync(remote, ledger, pageSize = 2).sync()

        // 2026-07-25 minus the 180-day local window. Comfortably inside the server's 18-month retention,
        // so nothing pruned here is unrecoverable — the next bootstrap could fetch it again.
        assertEquals(listOf(Instant.parse("2026-01-26T09:30:00Z")), ledger.pruneCutoffs)
    }

    // --- fixtures ---

    private fun page(entries: List<ActivityEntryDto>, nextSince: String?): RemoteSnapshot<ActivityFeedDto> =
        RemoteSnapshot.Available(ActivityFeedDto(entries = entries, nextSince = nextSince))

    private fun entry(
        id: String,
        actionKind: String = "updated",
        occurredAt: String = "2026-07-24T10:00:00Z",
        observedAt: String = "2026-07-24T10:00:01Z",
    ) = ActivityEntryDto(
        entryId = id,
        itemId = "item-$id",
        actionKind = actionKind,
        occurredAt = occurredAt,
        observedAt = observedAt,
    )
}

/**
 * A scripted [ActivityRemoteSource]: hands back [scripted] pages in order and records the `(since, limit)`
 * pair every request carried, which is how the paging tests assert the watermark walk.
 *
 * Running past the end of the script is a **failure**, not an empty page — over-paging is exactly the bug
 * the termination cases exist to catch, and a fake that quietly returned "nothing more" would hide it.
 * [endless] is the deliberate opt-out: the server that never runs dry, for the [ActivitySync] page bound.
 */
private class FakeActivityRemoteSource(
    vararg scripted: RemoteSnapshot<ActivityFeedDto>,
) : ActivityRemoteSource {

    private val pages = ArrayDeque(scripted.toList())

    /** Returned forever once the script drains — a server with more entries than one pass can take. */
    var endless: ActivityFeedDto? = null

    val calls = mutableListOf<Pair<String, Int>>()
    val sinceValues: List<String> get() = calls.map { it.first }
    val limits: List<Int> get() = calls.map { it.second }

    override suspend fun sync(since: String, limit: Int): RemoteSnapshot<ActivityFeedDto> {
        calls += since to limit
        pages.removeFirstOrNull()?.let { return it }
        val more = endless ?: error("the pager asked for page ${calls.size}, past the end of the script")
        return RemoteSnapshot.Available(more)
    }
}

/**
 * A recording [ActivityLedgerStore] for the reconcile tests: captures every merged page, every cursor
 * write (with the instant it was stamped at), and every prune cutoff, while serving the stored watermark.
 *
 * The local-write half of the port is unimplemented on purpose — [ActivitySync] must never record a local
 * row, so a call here is a bug worth failing loudly rather than a silently-tolerated no-op.
 */
private class RecordingActivityLedgerStore(initialCursor: String? = null) : ActivityLedgerStore {

    var storedCursor: String? = initialCursor
        private set

    val merges = mutableListOf<List<RemoteActivityEntry>>()
    val merged: List<RemoteActivityEntry> get() = merges.flatten()
    val mergedIds: List<String> get() = merged.map { it.entryId }
    val cursorWrites = mutableListOf<Pair<String?, Instant>>()
    val pruneCutoffs = mutableListOf<Instant>()

    override suspend fun recordLocal(
        source: ActivitySource,
        target: String,
        request: OutboxRequest,
        before: String?,
        now: Instant,
        stamp: ActivityStamp?,
        actionKind: ActivityActionKind?,
    ) {
        error("the reconcile must not record local rows")
    }

    override suspend fun upsertRemote(entries: List<RemoteActivityEntry>) {
        merges += entries
    }

    override fun recent(limit: Long): Flow<List<ActivityEntry>> = flowOf(emptyList())

    override suspend fun pruneOlderThan(cutoff: Instant) {
        pruneCutoffs += cutoff
    }

    override suspend fun syncCursor(): String? = storedCursor

    override suspend fun setSyncCursor(cursor: String?, now: Instant) {
        storedCursor = cursor
        cursorWrites += cursor to now
    }

    override suspend fun clear() {
        error("the reconcile must not clear the ledger")
    }
}

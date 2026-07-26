package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.network.dto.ActivityFeedDto
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Reconciles the local activity ledger against the server's (#364) — the client's one and only delta sync.
 *
 * ## Why `?since=` and not a snapshot
 *
 * Every other pull in this app is a full-snapshot reconcile (`GET /items`, then delete-what-the-server-
 * didn't-return). That policy is exactly wrong for a ledger: it is append-only and unbounded, so a
 * snapshot would grow without limit and a "purge what's missing" pass would delete history the server
 * simply hadn't paged yet. The reconcile here is **grow-only, unioned by `entry_id`** — it never deletes.
 *
 * ## Why the cursor is the only durable state
 *
 * `?since=` pages the server's `observed_at` axis ascending and **gaplessly**. That is a deliberate,
 * hard-won property: an earlier backend revision advanced an observed_at watermark over an
 * occurred_at-ordered page, which permanently skipped entries whose `observed_at` was in range but whose
 * `occurred_at` fell below the page cut — routine whenever a phone replays a backlog of offline-backdated
 * writes. The client's obligation is simply to persist the returned token and replay it verbatim; deriving
 * a timestamp of its own would reintroduce exactly that hole.
 *
 * ## Termination
 *
 * A full page means more may be waiting, so paging continues; a short page means caught up. Crucially the
 * loop does **not** stop on a null cursor — the last non-empty page still returns one — nor does it trust
 * the server to run dry, since a busy org could otherwise keep one client paging indefinitely. [maxPages]
 * bounds a single pass; whatever is left is picked up on the next tick.
 *
 * ## Single-flight
 *
 * [sync] is safe to call from anywhere at any time, which is the contract the shell's driver relies on —
 * it fires this from three independent legs (activation, the reconnect edge, and a slow periodic tick).
 * Two of those can overlap, and unguarded overlap costs real damage: both passes read the same watermark
 * and re-page the same rows, and the slower one then writes its **older** cursor last, rewinding the
 * watermark so the next tick re-pulls a page that had already landed. So a pass that finds one already in
 * flight simply returns. Nothing is lost by returning — the in-flight pass pages until it is caught up,
 * and it holds a cursor no older than the one the caller would have started from.
 */
class ActivitySync(
    private val remoteSource: ActivityRemoteSource,
    private val ledger: ActivityLedgerStore,
    private val now: () -> Instant = { Clock.System.now() },
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val bootstrapWindow: Duration = DEFAULT_BOOTSTRAP_WINDOW,
    private val retention: Duration = DEFAULT_RETENTION,
) {

    /** Held for the duration of a pass, so overlapping callers drop out rather than racing the cursor. */
    private val inFlight = Mutex()

    /**
     * Pull everything observed since the stored watermark and merge it, then trim the local window. A no-op
     * while another pass is already running (see **Single-flight** above).
     *
     * Best-effort by construction: a transport failure, a `401`, or the `503` an environment without a
     * configured ledger returns all leave the cursor and the cached rows exactly as they were, so the feed
     * keeps rendering what it already had and the next tick resumes from the same point. The ledger is a
     * diagnostics surface — it must never be the reason a sync pass reports failure.
     */
    suspend fun sync() {
        // tryLock, not withLock: a queued second pass would wake up against the watermark the first just
        // advanced and spend a request confirming there is nothing left. Dropping it is the same answer,
        // for free — and it bounds what an over-eager caller can queue up.
        if (!inFlight.tryLock()) return
        try {
            pageAndMerge()
        } finally {
            inFlight.unlock()
        }
    }

    /** One pass, always under [inFlight]: page from the stored watermark until caught up, then trim. */
    private suspend fun pageAndMerge() {
        var cursor = ledger.syncCursor() ?: bootstrapCursor()
        var pages = 0
        var merged = 0
        while (pages < maxPages) {
            val page = when (val result = remoteSource.sync(cursor, pageSize)) {
                is RemoteSnapshot.Available -> result.value
                RemoteSnapshot.Unavailable -> return
            }
            merged += merge(page)
            // Only advance past rows that actually landed. An empty page yields no cursor, which is the
            // caught-up signal: keep the watermark we already have rather than resetting it.
            cursor = page.nextSince ?: break
            ledger.setSyncCursor(cursor, now())
            pages++
            if (page.entries.size < pageSize) break
        }
        if (merged > 0) prune()
    }

    /**
     * Merge one page, dropping only entries whose timestamps cannot be parsed (see [toRemote]). Returns how
     * many rows actually landed — the signal [sync] gates the prune on.
     */
    private suspend fun merge(page: ActivityFeedDto): Int {
        val entries = page.entries.mapNotNull { it.toRemote() }
        ledger.upsertRemote(entries)
        return entries.size
    }

    /**
     * The first-sync watermark: a bare RFC-3339 timestamp, which the endpoint accepts in place of an opaque
     * token. Bounded rather than "everything" so a long-lived account doesn't pay for its entire history on
     * first launch — the ADR's "keep a bounded window ⊆ the server's".
     */
    private fun bootstrapCursor(): String = (now() - bootstrapWindow).toString()

    /**
     * Keep the local window a strict subset of the server's, so nothing pruned here is unrecoverable.
     *
     * Run only after a pass that merged something. This tick fires every five minutes for the life of the
     * session and the reconcile is the only thing that GROWS this table — so a pass that merged nothing
     * cannot have pushed anything past the window that was not already past it, and paying a DELETE (a
     * write transaction, through SQLCipher on Android) to rediscover that is pure overhead. The cost is
     * that a wholly idle account keeps rows slightly past 180 days until its next merge, which is
     * invisible: the feed reads with a LIMIT and the server's own window is 18 months.
     */
    private suspend fun prune() {
        ledger.pruneOlderThan(now() - retention)
    }

    companion object {
        /** The server's own default; its hard maximum is 100. */
        const val DEFAULT_PAGE_SIZE: Int = 50

        /** ~1000 entries per pass — enough to catch up a busy org quickly without monopolising a tick. */
        const val DEFAULT_MAX_PAGES: Int = 20

        /** How far back a fresh install reaches on its very first sync. */
        val DEFAULT_BOOTSTRAP_WINDOW: Duration = 30.days

        /** The local window. Comfortably inside the server's 18-month retention, so a prune is never a loss. */
        val DEFAULT_RETENTION: Duration = 180.days
    }
}

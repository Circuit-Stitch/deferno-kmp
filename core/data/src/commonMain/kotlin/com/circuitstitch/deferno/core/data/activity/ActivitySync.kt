package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.network.dto.ActivityFeedDto
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

    /**
     * Pull everything observed since the stored watermark and merge it, then trim the local window.
     *
     * Best-effort by construction: a transport failure, a `401`, or the `503` an environment without a
     * configured ledger returns all leave the cursor and the cached rows exactly as they were, so the feed
     * keeps rendering what it already had and the next tick resumes from the same point. The ledger is a
     * diagnostics surface — it must never be the reason a sync pass reports failure.
     */
    suspend fun sync() {
        var cursor = ledger.syncCursor() ?: bootstrapCursor()
        var pages = 0
        while (pages < maxPages) {
            val page = when (val result = remoteSource.sync(cursor, pageSize)) {
                is RemoteSnapshot.Available -> result.value
                RemoteSnapshot.Unavailable -> return
            }
            merge(page)
            // Only advance past rows that actually landed. An empty page yields no cursor, which is the
            // caught-up signal: keep the watermark we already have rather than resetting it.
            cursor = page.nextSince ?: break
            ledger.setSyncCursor(cursor, now())
            pages++
            if (page.entries.size < pageSize) break
        }
        prune()
    }

    /** Merge one page, dropping only entries whose timestamps cannot be parsed (see [toRemote]). */
    private suspend fun merge(page: ActivityFeedDto) {
        val entries = page.entries.mapNotNull { it.toRemote() }
        ledger.upsertRemote(entries)
    }

    /**
     * The first-sync watermark: a bare RFC-3339 timestamp, which the endpoint accepts in place of an opaque
     * token. Bounded rather than "everything" so a long-lived account doesn't pay for its entire history on
     * first launch — the ADR's "keep a bounded window ⊆ the server's".
     */
    private fun bootstrapCursor(): String = (now() - bootstrapWindow).toString()

    /** Keep the local window a strict subset of the server's, so nothing pruned here is unrecoverable. */
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

package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The outcome counts of one [OutboxProcessor.flush] pass. [dropped] counts entries **dead-lettered** this
 * pass (terminally rejected / attempts-exhausted) — they are preserved in the queue ([OutboxEntry.failedAt]),
 * not deleted; [remaining] is the live (still-syncable) queue size.
 */
data class FlushResult(
    val succeeded: Int,
    val dropped: Int,
    val retried: Int,
    val remaining: Long,
)

/**
 * The offline replay engine (ADR-0001, #23): drains the [OutboxStore] FIFO when connectivity returns,
 * retrying with backoff and re-reconciling the cache after a successful flush. The app calls [flush]
 * on a trigger (reconnect / foreground); `now` is passed in so the readiness + backoff timing is fully
 * deterministic under test (ADR-0006 JVM-fast path).
 *
 * **Strict FIFO, head-of-line.** Entries replay in [OutboxEntry.seq] (enqueue) order. A *transient*
 * failure on the head ([SendOutcome.Retryable]) backs the head off and **stops** the pass — later
 * entries wait — so independent intents never overtake an earlier one (LWW ordering, ADR-0001). A
 * *rejected* entry ([SendOutcome.Terminal]) is **dead-lettered** (preserved, [deadLetter]) and the pass
 * continues. To stop one permanently-failing entry starving the whole queue, a head that has retried
 * [maxAttempts] times is likewise dead-lettered so the queue can make progress. A dead-lettered entry is
 * never deleted and its optimistic local value is never undone — the user's write is preserved (never
 * silently dropped); it simply stops replaying (excluded from [OutboxStore.syncable], so no later pass
 * sees it).
 *
 * **Reconcile after a successful flush.** If at least one entry succeeded this pass, [reconcile] runs
 * once at the end — the app binds it to its repository refresh(es), which re-pull the full snapshot and
 * LWW-merge server truth over the optimistic local state (ADR-0001 "re-reconcile after each flush").
 * It is best-effort: a thrown reconcile is swallowed (the already-dispatched entries stay dispatched;
 * the next flush re-triggers it). A flush that only dead-letters/retries does not reconcile — there is
 * no new server state to pull.
 *
 * **Create entries replay specially (#185).** An entry whose target is `create:<kind>:<id>` is an
 * offline create: it replays through the response-bearing [OutboxRequestSender.sendCreate] (the server
 * id is needed to confirm the pending-create row and to heal a divergent canonical id), and resolves
 * through the [CreateReplayListener] — confirm on success. On terminal rejection it is dead-lettered like
 * any other entry (the optimistic insert is preserved, not undone). If a heal re-points queued entries,
 * the pass stops (its `syncable()` snapshot is stale) and
 * the next flush re-reads. A `comment-create:<taskId>:<clientId>` entry (ADR-0043) replays through the
 * **same** response-bearing path but resolves through the [CommentReplayListener] — the backend never
 * honours the client comment id, so *every* comment-create rekeys the row and re-points its queued edits;
 * when it does, the pass stops too. Both schemes run one policy: [routeFor] parses the target and binds
 * whichever listener applies to a single [ReplayRoute]. Every other entry is a fire-and-forget edit via
 * [OutboxRequestSender.send].
 *
 * [flush] is guarded by a [Mutex] so two concurrent triggers can't double-dispatch the same entry.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val sender: OutboxRequestSender,
    private val reconcile: suspend () -> Unit,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoff: (Int) -> Duration = ::exponentialBackoff,
    // The offline-create replay seam (#185); default no-op so the engine's own tests need no wiring.
    private val createListener: CreateReplayListener = CreateReplayListener.NoOp,
    // The offline comment-create replay seam (ADR-0043); default no-op, beside [createListener].
    private val commentListener: CommentReplayListener = CommentReplayListener.NoOp,
) {
    private val mutex = Mutex()
    private val log = Logger("OutboxProcessor")

    /** Drains the ready entries at [now], applying the FIFO/backoff/head-of-line policy above. */
    suspend fun flush(now: Instant): FlushResult = mutex.withLock {
        var succeeded = 0
        var dropped = 0
        var retried = 0

        for (entry in store.syncable()) {
            // [syncable] is live rows only — a dead-lettered entry (a prior terminal rejection) is already
            // excluded, so it never replays again and can't trip the head-of-line break below.
            //
            // Entries are in seq order; the first not-yet-ready entry (a backed-off head) stops the
            // pass, preserving strict FIFO — nothing behind it may overtake it.
            if (entry.nextAttemptAt > now) break

            // A response-bearing create replay (item-create #185 or comment-create ADR-0043): we need the
            // server id to confirm/rekey the optimistic row and to heal a divergent id. Both schemes share
            // one policy — only the parse + which listener resolves it differ ([routeFor]). Every other
            // entry is a fire-and-forget edit against an existing id (the plain send below).
            val route = routeFor(entry)
            if (route != null) {
                when (val outcome = sender.sendCreate(entry.request)) {
                    is CreateSendOutcome.Created -> {
                        val healed = route.onReplayed(outcome.serverId)
                        store.delete(entry.seq)
                        succeeded++
                        // A heal re-pointed queued entries in the store; the in-flight `syncable()`
                        // snapshot this loop walks is now stale, so stop and let the next flush re-read.
                        if (healed) break
                    }
                    CreateSendOutcome.Terminal -> {
                        deadLetter(entry, now)
                        dropped++
                    }
                    CreateSendOutcome.Retryable -> {
                        val attempts = entry.attempts + 1
                        if (attempts >= maxAttempts) {
                            deadLetter(entry, now)
                            dropped++
                        } else {
                            store.markRetry(entry.seq, attempts, now + backoff(attempts))
                            retried++
                            break
                        }
                    }
                }
                continue
            }

            when (sender.send(entry.request)) {
                SendOutcome.Success -> {
                    store.delete(entry.seq)
                    succeeded++
                }
                SendOutcome.Terminal -> {
                    deadLetter(entry, now)
                    dropped++
                }
                SendOutcome.Retryable -> {
                    val attempts = entry.attempts + 1
                    if (attempts >= maxAttempts) {
                        // Exhausted: give up so this entry can't block the queue forever. Continue the
                        // pass (don't break) — the now-unblocked tail gets its turn.
                        deadLetter(entry, now)
                        dropped++
                    } else {
                        store.markRetry(entry.seq, attempts, now + backoff(attempts))
                        retried++
                        break // head-of-line: hold the order until this entry clears
                    }
                }
            }
        }

        if (succeeded > 0) {
            try {
                reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Best-effort: the successful writes are already committed server-side; the next flush
                // (or an explicit refresh) re-pulls. Don't fail the flush over a reconcile hiccup.
            }
        }

        FlushResult(succeeded = succeeded, dropped = dropped, retried = retried, remaining = store.count())
    }

    /**
     * Dead-letter a terminally-rejected / attempts-exhausted [entry]: preserve it (never silently
     * delete — its optimistic local row stays too, and it keeps protecting that row from the reconcile
     * clobber-guards) and log the loss of sync so a bad request/contract is visible instead of vanishing.
     */
    private suspend fun deadLetter(entry: OutboxEntry, now: Instant) {
        store.markFailed(entry.seq, now)
        log.e {
            "dead-lettered ${entry.request.method} /${entry.request.path.joinToString("/")} " +
                "(target=${entry.target}, attempts=${entry.attempts}) — server terminally rejected it; " +
                "the write is preserved locally but will NOT sync until retried/discarded"
        }
    }

    /**
     * The response-bearing replay route for [entry], or `null` for a plain fire-and-forget edit. The two
     * create-target schemes are disjoint by prefix (`create:` vs `comment-create:` — the latter is not a
     * `create:` prefix-match), so each entry resolves to at most one route regardless of order. Binds the
     * matched target to its listener so [flush] runs one policy for both. A terminal rejection is handled
     * uniformly by [deadLetter] (not the listener) — the optimism is preserved, never undone.
     */
    private fun routeFor(entry: OutboxEntry): ReplayRoute? {
        CreateTarget.parse(entry.target)?.let { create ->
            return ReplayRoute(
                onReplayed = { serverId -> createListener.onReplayed(create.clientId, create.kind, serverId) },
            )
        }
        CommentTargets.parseCreate(entry.target)?.let { commentCreate ->
            return ReplayRoute(
                onReplayed = { serverId -> commentListener.onReplayed(commentCreate.taskId, commentCreate.clientId, serverId) },
            )
        }
        return null
    }

    /**
     * A resolved response-bearing create replay: [onReplayed] heals the optimistic row and returns whether
     * it re-pointed queued entries (so the pass must stop, its `syncable()` snapshot being stale). A
     * terminal rejection is dead-lettered by [deadLetter], not undone — the optimistic row is preserved.
     */
    private class ReplayRoute(
        val onReplayed: suspend (serverId: String) -> Boolean,
    )

    companion object {
        /**
         * The replay-attempt ceiling before an entry is abandoned so it can't starve the queue (#23).
         * Under [exponentialBackoff], 12 attempts span ≈18 minutes of sustained failure
         * (1+2+4+…+256s, then the 5-minute cap) before the write is dropped and left for the next
         * reconcile to correct (LWW).
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 12
    }
}

/**
 * The (kind, client id) decoded from a create entry's `create:<kind>:<id>` [OutboxEntry.target] (#185).
 * [parse] returns `null` for any non-create entry (every edit), so the processor only routes genuine
 * creates through the response-bearing path. Item ids are UUIDs (no `:`), so the kind is the segment
 * before the first `:` and the id is the remainder.
 */
private data class CreateTarget(val kind: ItemKind, val clientId: String) {
    companion object {
        fun parse(target: String): CreateTarget? {
            if (!target.startsWith(PREFIX)) return null
            val rest = target.removePrefix(PREFIX)
            val kindName = rest.substringBefore(':')
            val id = rest.substringAfter(':', "")
            val kind = ItemKind.entries.firstOrNull { it.name == kindName } ?: return null
            return if (id.isBlank()) null else CreateTarget(kind, id)
        }

        private const val PREFIX = "create:"
    }
}

/**
 * The default replay backoff (#23): exponential from 1s, doubling per attempt, capped at 5 minutes.
 * [attempts] is the new (1-based) attempt count — `backoff(1) == 1s`, `backoff(2) == 2s`, … reaching
 * the 5-minute cap by attempt 9 and holding there. The shift is clamped so the doubling can't overflow.
 */
fun exponentialBackoff(attempts: Int): Duration {
    val base = 1.seconds
    val cap = 5.minutes
    val shift = (attempts - 1).coerceIn(0, 30)
    val scaled = (base.inWholeMilliseconds shl shift).milliseconds
    return if (scaled < cap) scaled else cap
}

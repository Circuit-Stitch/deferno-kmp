package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.activity.LocalActivityChange
import com.circuitstitch.deferno.core.data.activity.withActivityStamp
import kotlin.time.Instant

/**
 * The single choke-point that feeds the activity ledger (#260, extended in #364): an [OutboxStore]
 * decorator that, for every enqueued write, mints a client [ActivityStamp], merges it onto the request
 * that goes on the wire, and records the write locally — all with no per-writer edits, so a new write path
 * that enqueues is covered for free and one cannot be forgotten.
 *
 * ## Why the stamp and the ledger row are minted together, here
 *
 * The stamp's `entryId` is the **merge key** between this optimistic row and the authoritative row the
 * server files for the same action. They must therefore be the same value, and the only way to guarantee
 * that is to mint once and use it for both — which is only possible at a point that sees both the request
 * and the ledger. Splitting stamping into its own decorator would either mint twice or make correctness
 * depend on the order the two decorators happen to be wired in, which is a trap for whoever wires them next.
 *
 * Because the outbox persists the *rendered* request and replays it verbatim, the stamp is also stable
 * across every retry — exactly what the server's `ON CONFLICT (entry_id) DO NOTHING` insert wants.
 *
 * ## What the ledger records is the UNSTAMPED body — deliberately
 *
 * The delegate (and so the wire) gets the stamped request; the ledger gets the original. `activity` is
 * metadata *about* the change, not a field the user changed, and the read-time diff treats every body key
 * as a changed field — so recording the stamped body would surface a bogus row in the Activity detail
 * sheet and the Task Trail.
 *
 * Every row lands as a local app-side write — the ledger's own invariant rather than an argument this
 * decorator supplies. Rows from every other surface ("via Website" / "via MCP agent" / API / system)
 * arrive through the ledger's `?since=` reconcile instead.
 *
 * The ledger write stays best-effort: a ledger failure must never lose or block the user's actual write,
 * so it is swallowed — the outbox enqueue, the durable and replayed source of truth, has already
 * succeeded. Note the ordering consequence: a write whose ledger row is lost still syncs, and its server
 * twin still arrives on the next reconcile, so the feed self-heals.
 */
class LedgerRecordingOutboxStore(
    private val delegate: OutboxStore,
    private val ledger: ActivityLedgerStore,
    private val mintStamp: (Instant) -> ActivityStamp = ActivityStamp::mint,
) : OutboxStore {

    override suspend fun enqueue(target: String, request: OutboxRequest, now: Instant, before: String?) {
        // Only mint where the route declared it can carry one ([OutboxRequest.acceptsActivityStamp]): an
        // unexpected key on a strict payload is a 422, which the sender treats as Terminal and
        // dead-letters — losing the user's write, not just an audit row. A route that can't carry a stamp
        // still gets a local row; it is simply superseded by the server's own rather than merged with it.
        val stamp = if (request.acceptsActivityStamp) mintStamp(now) else null
        val outbound = stamp?.let(request::withActivityStamp) ?: request
        delegate.enqueue(target, outbound, now, before)
        // `request`, not `outbound`: the ledger keeps the UNSTAMPED body (see above).
        val change = LocalActivityChange(target, request.method, request.path, request.body, before)
        runCatching { ledger.recordLocal(change, at = now, stamp = stamp) }
    }

    override suspend fun syncable(): List<OutboxEntry> = delegate.syncable()

    override suspend fun allUnsynced(): List<OutboxEntry> = delegate.allUnsynced()

    override suspend fun delete(seq: Long) = delegate.delete(seq)

    override suspend fun markFailed(seq: Long, failedAt: Instant) = delegate.markFailed(seq, failedAt)

    override suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant) =
        delegate.markRetry(seq, attempts, nextAttemptAt)

    override suspend fun update(seq: Long, target: String, request: OutboxRequest) =
        delegate.update(seq, target, request)

    override suspend fun count(): Long = delegate.count()
}

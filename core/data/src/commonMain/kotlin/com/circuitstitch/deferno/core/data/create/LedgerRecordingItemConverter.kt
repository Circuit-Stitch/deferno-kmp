package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Brings **convert** into the activity ledger (#364) — the third and last write surface that never reaches
 * the outbox choke-point, after [com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore]
 * (everything queued) and [com.circuitstitch.deferno.core.data.task.LedgerRecordingTaskDetailRepository]
 * (attachments).
 *
 * ## Why convert needs its own decorator
 *
 * Converting an existing item changes its KIND, which has no meaningful optimistic apply and no client-id
 * idempotency story, so it stays online-only (ADR-0016) and never enqueues. Recording it inside
 * [OfflineCreateWriter] instead — which is where it started — put a ledger dependency on a class whose
 * other four methods have nothing to do with the ledger, and made convert the one write whose audit row
 * was a writer's private responsibility rather than a seam's. That is how the next write path forgets.
 *
 * ## Why the stamp is minted here and not in the Ktor layer
 *
 * Same reason as the other two decorators: the stamp's `entryId` is the merge key between the optimistic
 * row written here and the authoritative row the server files for the same action, so it must be minted
 * once by whoever writes both. [KtorItemRemoteSource] stays a pure wire adapter carrying what it is handed.
 *
 * ## Recorded only on success
 *
 * Unlike an enqueued write — durable the moment it is queued, so recorded unconditionally — a convert
 * either reached the server now or never happened. A row for a failed convert would claim a kind change
 * the user's data does not have, and no reconcile would ever take it back. The record itself stays
 * best-effort: a ledger failure must not turn a successful convert into a reported failure.
 *
 * The row names [ActivityActionKind.Converted] explicitly because its `item:{id}` target would otherwise
 * read as "Moved an item" — and because the server names the same verb on its own row, which is what makes
 * the two agree before the reconcile arrives to confirm it.
 */
internal class LedgerRecordingItemConverter(
    private val delegate: StampedItemConverter,
    private val ledger: ActivityLedgerStore,
    private val now: () -> Instant = { Clock.System.now() },
    private val mintStamp: (Instant) -> ActivityStamp = ActivityStamp::mint,
) : ItemConverter {

    override suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem> {
        val at = now()
        val stamp = mintStamp(at)
        val result = delegate.convert(id, payload, stamp)
        if (result is ApiResult.Success) {
            runCatching {
                ledger.recordLocal(
                    source = ActivitySource.Mobile,
                    target = "item:$id",
                    request = OutboxRequest(OutboxMethod.Post, listOf("items", id, "convert"), body = null),
                    before = null,
                    now = at,
                    stamp = stamp,
                    actionKind = ActivityActionKind.Converted,
                )
            }
        }
        return result
    }
}

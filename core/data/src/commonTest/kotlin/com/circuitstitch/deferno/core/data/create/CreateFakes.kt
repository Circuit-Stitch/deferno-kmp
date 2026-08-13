package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.activity.LocalActivityChange
import com.circuitstitch.deferno.core.data.activity.RemoteActivityEntry
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

/** A [Connectivity] whose online/offline state is set by the test (`online.value = …`). */
class FakeConnectivity(online: Boolean = true) : Connectivity {
    override val online = MutableStateFlow(online)
}

/**
 * An [ItemConverter] whose response the test configures, recording each call so a test can assert that an
 * offline convert reached the network zero times (ADR-0016). Unset defaults to a transport failure.
 *
 * Only the app-facing (stamp-free) half: what the stamped half receives is
 * [LedgerRecordingItemConverter]'s business, pinned in LedgerStampingTest. It is the create writer's only
 * network dependency — creates enqueue on the outbox (ADR-0034), so convert is the sole call that leaves
 * the device from there.
 */
class FakeItemConverter : ItemConverter {
    var convertResult: ApiResult<ConvertedItem> = ApiResult.Failure(ApiError.Transport(RuntimeException("unset")))

    val calls = mutableListOf<String>()

    override suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem> {
        calls += "convert:$id"; return convertResult
    }
}

/**
 * One [ActivityLedgerStore.recordLocal] call captured whole. Every argument is kept because the ledger's
 * correctness lives in the arguments a caller chose — which body it recorded, which stamp it reused,
 * which verb it named — not in the fact that it recorded at all.
 */
data class RecordedLocalActivity(
    val change: LocalActivityChange,
    val at: Instant,
    val stamp: ActivityStamp?,
    val actionKind: ActivityActionKind?,
) {
    val target: String get() = change.target
    val before: String? get() = change.before
}

/**
 * A recording [ActivityLedgerStore] for the write seams that record into the ledger (#364). Every
 * [recordLocal] call is kept whole because their correctness lives in the arguments each seam chose; the
 * rest of the port is implemented rather than stubbed away so a new ledger method can't slip past these
 * fixtures unnoticed.
 */
class FakeActivityLedgerStore : ActivityLedgerStore {
    val recorded = mutableListOf<RecordedLocalActivity>()
    val merged = mutableListOf<RemoteActivityEntry>()
    var cursor: String? = null

    /**
     * When set, every [recordLocal] throws it. Recording is best-effort at every call site — the user's
     * actual write must survive a ledger that can't take the row — so the failure path needs exercising.
     */
    var recordLocalFailure: Throwable? = null

    override suspend fun recordLocal(
        change: LocalActivityChange,
        at: Instant,
        stamp: ActivityStamp?,
        actionKind: ActivityActionKind?,
    ) {
        recordLocalFailure?.let { throw it }
        recorded += RecordedLocalActivity(change, at, stamp, actionKind)
    }

    override suspend fun upsertRemote(entries: List<RemoteActivityEntry>) {
        merged += entries
    }

    override fun recent(limit: Long): Flow<List<ActivityEntry>> = flowOf(emptyList())

    override suspend fun pruneOlderThan(cutoff: Instant) = Unit

    override suspend fun syncCursor(): String? = cursor

    override suspend fun setSyncCursor(cursor: String?, now: Instant) {
        this.cursor = cursor
    }

    override suspend fun clear() {
        recorded.clear()
        merged.clear()
        cursor = null
    }
}

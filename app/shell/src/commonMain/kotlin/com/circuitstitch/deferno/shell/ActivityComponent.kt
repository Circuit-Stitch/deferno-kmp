package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.itemId
import com.circuitstitch.deferno.core.data.activity.summaryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/**
 * One row of the Activity feed — a render-ready projection of an [ActivityEntry] (#260): what changed
 * ([summaryInfo], typed for locale-aware rendering), who made it ([source]), and when ([recordedAt]).
 * [itemId] is the thing it touched (a future tap-to-open target; the v1 feed is read-only). Every
 * platform View localizes from the typed [summaryInfo] / [source] (#327).
 */
data class ActivityFeedRow(
    val seq: Long,
    val recordedAt: Instant,
    val itemId: String?,
    val summaryInfo: ActivitySummary,
    val source: ActivitySource,
)

/** The Activity Destination render state: the recorded changes, newest first. */
data class ActivityFeedState(
    val rows: List<ActivityFeedRow> = emptyList(),
)

/**
 * The **Activity** Destination (#260): a read-only, reverse-chronological feed of every change the app
 * has made, sourced from the offline-first activity ledger. Compose-free so the View is a thin render of
 * [state]. Server-sourced ("via Website" / "via MCP") rows arrive here too once the reconcile seam tags
 * them — no View change needed.
 */
interface ActivityComponent {
    val state: StateFlow<ActivityFeedState>
}

/**
 * Default [ActivityComponent]. [observeActivity] is the ledger's reverse-chron feed (the Account's
 * `activityLedgerStore.recent()`, wired by the shell); this maps each entry to its render projection.
 */
class DefaultActivityComponent(
    componentContext: ComponentContext,
    observeActivity: () -> Flow<List<ActivityEntry>>,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ActivityComponent, ComponentContext by componentContext {

    override val state: StateFlow<ActivityFeedState> =
        observeActivity()
            .map { entries -> ActivityFeedState(entries.map { it.toRow() }) }
            .stateIn(componentScope(coroutineContext), SharingStarted.WhileSubscribed(5_000L), ActivityFeedState())
}

private fun ActivityEntry.toRow(): ActivityFeedRow =
    ActivityFeedRow(
        seq = seq,
        recordedAt = recordedAt,
        itemId = itemId(),
        summaryInfo = summaryInfo(),
        source = source,
    )

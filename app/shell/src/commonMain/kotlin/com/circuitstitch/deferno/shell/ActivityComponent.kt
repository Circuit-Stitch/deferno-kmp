package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.changes
import com.circuitstitch.deferno.core.data.activity.itemId
import com.circuitstitch.deferno.core.data.activity.summaryInfo
import com.circuitstitch.deferno.core.model.ActivityFieldChange
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
 * [itemId] is the thing it touched — the tap-to-open target the detail sheet's "Open item" routes to
 * (null where there's no single item, e.g. a plan/settings row). [changes] is the typed old->new field
 * diff the detail sheet renders (empty when nothing was captured). Every platform View localizes from the
 * typed [summaryInfo] / [source] / [changes] (#327).
 */
data class ActivityFeedRow(
    val seq: Long,
    val recordedAt: Instant,
    val itemId: String?,
    val summaryInfo: ActivitySummary,
    val source: ActivitySource,
    val changes: List<ActivityFieldChange>,
)

/** The Activity Destination render state: the recorded changes, newest first. */
data class ActivityFeedState(
    val rows: List<ActivityFeedRow> = emptyList(),
)

/**
 * The **Activity** Destination (#260): a reverse-chronological feed of every change the app has made,
 * sourced from the offline-first activity ledger. Compose-free so the View is a thin render of [state];
 * tapping a row opens a detail sheet whose "Open item" emits [Output.OpenItem] via [openItem], which the
 * shell routes to the changed item. Server-sourced ("via Website" / "via MCP") rows arrive here too once
 * the reconcile seam tags them — no View change needed.
 */
interface ActivityComponent {
    val state: StateFlow<ActivityFeedState>

    /** Ask the host to open the item a row touched (the detail sheet's "Open item"). */
    fun openItem(id: String)

    sealed interface Output {
        /** Open the item [id] a change touched — the shell switches to Tasks and drills into it. */
        data class OpenItem(val id: String) : Output
    }
}

/**
 * Default [ActivityComponent]. [observeActivity] is the ledger's reverse-chron feed (the Account's
 * `activityLedgerStore.recent()`, wired by the shell); this maps each entry to its render projection.
 * [output] bubbles the "open item" intent to the shell (mirrors the Search/Settings deep-links).
 */
class DefaultActivityComponent(
    componentContext: ComponentContext,
    observeActivity: () -> Flow<List<ActivityEntry>>,
    private val output: (ActivityComponent.Output) -> Unit = {},
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ActivityComponent, ComponentContext by componentContext {

    override val state: StateFlow<ActivityFeedState> =
        observeActivity()
            .map { entries -> ActivityFeedState(entries.map { it.toRow() }) }
            .stateIn(componentScope(coroutineContext), SharingStarted.WhileSubscribed(5_000L), ActivityFeedState())

    override fun openItem(id: String) = output(ActivityComponent.Output.OpenItem(id))
}

private fun ActivityEntry.toRow(): ActivityFeedRow =
    ActivityFeedRow(
        seq = seq,
        recordedAt = recordedAt,
        itemId = itemId(),
        summaryInfo = summaryInfo(),
        source = source,
        changes = changes(),
    )

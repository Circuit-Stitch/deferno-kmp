package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.changes
import com.circuitstitch.deferno.core.data.activity.commentBody
import com.circuitstitch.deferno.core.data.activity.commentTaskId
import com.circuitstitch.deferno.core.data.activity.itemId
import com.circuitstitch.deferno.core.data.activity.summaryInfo
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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
 *
 * [itemRef] / [itemKind] are resolved at read-time by joining [itemId] against the item cache (#260): the
 * short ref (`"#45"`, from the item's `sequence`) the row/label read as "Updated task #41" / "Open Task
 * #99", and the item's kind for that label. Both `null` when the id doesn't resolve — a brand-new item
 * whose `sequence` the server hasn't assigned yet, or one aged out / deleted from the cache — so the View
 * falls back to the plain phrasing. [commentBody] is the text on a comment post/edit row (else `null`),
 * shown as the row snippet + the sheet's note.
 */
data class ActivityFeedRow(
    val seq: Long,
    val recordedAt: Instant,
    val itemId: String?,
    val summaryInfo: ActivitySummary,
    val source: ActivitySource,
    val changes: List<ActivityFieldChange>,
    val itemRef: String? = null,
    val itemKind: ItemKind? = null,
    val commentBody: String? = null,
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
 * `activityLedgerStore.recent()`, wired by the shell); [observeItems] is the cross-kind item cache
 * (`ItemRepository.observeItems()`) it joins each row against to resolve the item ref + kind at read-time.
 * This maps each entry to its render projection. [output] bubbles the "open item" intent to the shell
 * (mirrors the Search/Settings deep-links).
 */
class DefaultActivityComponent(
    componentContext: ComponentContext,
    observeActivity: () -> Flow<List<ActivityEntry>>,
    observeItems: () -> Flow<List<Item>>,
    private val output: (ActivityComponent.Output) -> Unit = {},
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : ActivityComponent, ComponentContext by componentContext {

    // combine emits on either source; the StateFlow dedupes by equals(), so an item-cache change that
    // touches no referenced item is suppressed. A just-created row (sequence still null → plain phrasing)
    // gains its ref live once sync assigns the sequence and observeItems() re-emits.
    override val state: StateFlow<ActivityFeedState> =
        // onStart seeds the item cache so combine emits the ledger rows the instant they arrive (plain, then
        // enriched) rather than waiting on the first item-cache emission — no blank feed if that query lags.
        combine(observeActivity(), observeItems().onStart { emit(emptyList()) }) { entries, items ->
            val byId = items.associateBy { it.id }
            ActivityFeedState(entries.map { it.toRow(byId) })
        }.stateIn(componentScope(coroutineContext), SharingStarted.WhileSubscribed(5_000L), ActivityFeedState())

    override fun openItem(id: String) = output(ActivityComponent.Output.OpenItem(id))
}

private fun ActivityEntry.toRow(byId: Map<String, Item>): ActivityFeedRow {
    // Effective id resolves a comment row to its task too (comment targets have no itemId()); kept local
    // to the feed so the shared itemId() — the Task Trail's ledger filter — is unchanged.
    val effectiveId = itemId() ?: commentTaskId()
    val item = effectiveId?.let(byId::get)
    return ActivityFeedRow(
        seq = seq,
        recordedAt = recordedAt,
        itemId = effectiveId,
        summaryInfo = summaryInfo(),
        source = source,
        changes = changes(),
        itemRef = item?.sequence?.let { "#$it" },
        itemKind = item?.kind,
        commentBody = commentBody(),
    )
}

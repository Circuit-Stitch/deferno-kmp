package com.circuitstitch.deferno.feature.braindumps

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnResume
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The outcome of accepting a draft. Accept commits through the **online-only** create path (ADR-0016),
 * so it can refuse for want of connectivity ([Offline]) or fail at the server ([Failed]) — the Inbox
 * surfaces each gently and **keeps the draft**, never a silent loss (design-principles #1/#4).
 */
sealed interface AcceptResult {
    /** Created — the draft was committed as a real Task and has left the Ready queue (marked Accepted). */
    data object Accepted : AcceptResult

    /** No connectivity (ADR-0016): nothing was sent; the draft stays Ready for a later retry. */
    data object Offline : AcceptResult

    /** The server rejected the create: the draft stays Ready and [message] explains why. */
    data class Failed(val message: String) : AcceptResult
}

/** A gentle note after a non-accepting outcome — typed so the View can localize the fixed arms. */
sealed interface InboxNote {
    /** The create is queued behind connectivity — the View renders the localized "Reconnect to save". */
    data object Offline : InboxNote

    /** A server-authored error message — rendered verbatim (not client-localizable). */
    data class ServerMessage(val text: String) : InboxNote
}

/** One Ready draft as rendered in the Inbox, with any transient accept state. */
data class InboxRow(
    val draft: BrainDumpDraft,
    /** The accept create is in flight (the row shows progress, taps are ignored). */
    val accepting: Boolean = false,
    /** A gentle, typed note after a non-accepting outcome (offline / server error), else null —
     *  every platform View localizes the fixed arm and renders server prose verbatim (#327). */
    val noteKind: InboxNote? = null,
)

/** The Inbox render state: the Ready drafts, plus the just-dismissed one for a brief Undo. */
data class InboxState(
    val rows: List<InboxRow> = emptyList(),
    /** The just-dismissed draft, surfaced for Undo — nothing is deleted, so dismiss is recoverable. */
    val recentlyDismissed: BrainDumpDraft? = null,
)

/**
 * The **Inbox** Destination (ADR-0015 Inbox amendment): the triage queue for the persisted Brain dump
 * draft Tasks the [Extractor] produced. The person clears it out — *accept* commits a draft as a real
 * Task and removes it from the queue; *dismiss* drops it (recoverably). Compose-free so the View is a
 * thin render of [state].
 */
interface InboxComponent {
    val state: StateFlow<InboxState>

    /** Commit the draft as a real Task through the online-only create seam (ADR-0016/0027). */
    fun onAccept(id: BrainDumpDraftId)

    /** Drop the draft from the queue (marked Dismissed — recoverable via [onUndoDismiss], not deleted). */
    fun onDismiss(id: BrainDumpDraftId)

    /** Restore the most recently dismissed draft to the Ready queue. */
    fun onUndoDismiss()

    /** Clear a row's gentle offline/error note (e.g. when the person reconnects and retries). */
    fun onClearNote(id: BrainDumpDraftId)
}

/**
 * Default [InboxComponent]. [observeDrafts] is the live draft query (all statuses; this filters to
 * Ready); [accept] commits one draft online and — on success — marks it Accepted so it leaves the queue
 * and is never re-created (create isn't idempotent, ADR-0016); [upsert] persists a status change
 * (dismiss / undo). The shell wires these from the Account's `brainDumpDraftRepository` + the create seam.
 *
 * **Cross-driver visibility (Stage 2):** the brain-dump worker persists Ready drafts via its OWN
 * SQLDelight driver, so its inserts don't fire this (UI driver's) live query. The component therefore
 * **re-queries on Decompose resume** — a fresh `selectAll()` reads the worker's committed rows. The
 * same-driver writes here (accept's mark-Accepted, dismiss's mark-Dismissed) update the list live.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest — re-subscribe the draft query on each resume.
class DefaultInboxComponent(
    componentContext: ComponentContext,
    observeDrafts: () -> Flow<List<BrainDumpDraft>>,
    private val accept: suspend (BrainDumpDraft) -> AcceptResult,
    private val upsert: suspend (BrainDumpDraft) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : InboxComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    // Bumped on each Decompose resume to force a fresh draft query (see the cross-driver note above).
    private val resumeTick = MutableStateFlow(0)
    private val ready = MutableStateFlow<List<BrainDumpDraft>>(emptyList())
    private val accepting = MutableStateFlow<Set<BrainDumpDraftId>>(emptySet())
    private val notes = MutableStateFlow<Map<BrainDumpDraftId, InboxNote>>(emptyMap())
    private val dismissed = MutableStateFlow<BrainDumpDraft?>(null)

    init {
        scope.launch {
            resumeTick
                .flatMapLatest { observeDrafts() }
                .collect { drafts -> ready.value = drafts.filter { it.status == BrainDumpDraftStatus.Ready } }
        }
        lifecycle.doOnResume { resumeTick.value++ }
    }

    override val state: StateFlow<InboxState> =
        combine(ready, accepting, notes, dismissed) { rows, inFlight, msgs, justDismissed ->
            InboxState(
                rows = rows.map {
                    InboxRow(it, accepting = it.id in inFlight, noteKind = msgs[it.id])
                },
                recentlyDismissed = justDismissed,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), InboxState())

    override fun onAccept(id: BrainDumpDraftId) {
        val draft = ready.value.firstOrNull { it.id == id } ?: return
        if (id in accepting.value) return // idempotent: ignore taps while a create is in flight
        accepting.value = accepting.value + id
        notes.value = notes.value - id
        scope.launch {
            try {
                when (val r = accept(draft)) {
                    AcceptResult.Accepted -> Unit // marked Accepted by the seam → leaves the Ready list
                    AcceptResult.Offline -> notes.value = notes.value + (id to InboxNote.Offline)
                    is AcceptResult.Failed -> notes.value = notes.value + (id to InboxNote.ServerMessage(r.message))
                }
            } finally {
                accepting.value = accepting.value - id
            }
        }
    }

    override fun onDismiss(id: BrainDumpDraftId) {
        val draft = ready.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            upsert(draft.copy(status = BrainDumpDraftStatus.Dismissed))
            dismissed.value = draft
        }
    }

    override fun onUndoDismiss() {
        val draft = dismissed.value ?: return
        // Restore to Ready FIRST, then clear the Undo affordance — so a failed write leaves the Undo
        // available (the draft stays recoverable) rather than silently hiding a Dismissed draft.
        scope.launch {
            upsert(draft.copy(status = BrainDumpDraftStatus.Ready))
            dismissed.value = null
        }
    }

    override fun onClearNote(id: BrainDumpDraftId) {
        notes.value = notes.value - id
    }
}

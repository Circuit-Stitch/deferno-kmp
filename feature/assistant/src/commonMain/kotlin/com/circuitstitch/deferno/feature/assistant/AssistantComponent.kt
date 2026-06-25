package com.circuitstitch.deferno.feature.assistant

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.ChatRole
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The shared [[Assistant]] chat component (#282, ADR-0040): a thin client over the backend's
 * server-mediated conversational AI. It owns the chat state machine — availability gating, the
 * enable + egress-consent flow, the streaming turn (persisted as it streams), inline
 * [[Assistant proposal]] confirm/reject + server apply + re-sync, the multi-conversation switcher with
 * cross-device hydration, and the offline / usage-exhausted hard-stops.
 *
 * The local [ConversationStore] is the single source for messages + the switcher (so streamed upserts and
 * hydration backfill both flow into state reactively); turns are never outbox-queued (online-only to
 * extend, ADR-0040). Cross-feature effects (an applied change reflecting in Tasks/Plan) go through
 * [resyncAfterApply], which deliberately bypasses the Command/outbox write seam — here the server writes.
 */
interface AssistantComponent {
    val state: StateFlow<AssistantState>

    fun onComposerChanged(text: String)
    fun onSend()
    fun onCancelTurn()

    fun onNewConversation()
    fun onSelectConversation(id: ConversationId)

    fun onEnableRequested()
    fun onConsentAccepted()
    fun onConsentDeclined()

    fun onConfirmProposal()
    fun onRejectProposal()

    fun onDismissError()
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class DefaultAssistantComponent(
    componentContext: ComponentContext,
    private val orgId: OrgId,
    private val client: AssistantClient,
    private val store: ConversationStore,
    private val stream: AssistantStream,
    private val connectivity: Connectivity,
    // The per-Org availability gate (ADR-0040, #282) — a SHARED source the shell also feeds the Settings
    // enablement row, so the two surfaces never diverge (enable here ⇒ Settings reflects it, and vice
    // versa). Observed into state; `null` while loading / when the Assistant doesn't apply.
    private val availability: StateFlow<AssistantAvailability?> = MutableStateFlow(null),
    // Flip enablement server-side (the egress consent on enable); the result lands in [availability], so the
    // component only owns the enabling/disclosure UI state. The shell write-through returns null on failure.
    private val setEnabled: suspend (Boolean) -> AssistantAvailability? = { null },
    // After a confirmed proposal applies server-side, re-sync the affected items through the normal sync
    // path (NOT the outbox — the server is the writer here, ADR-0040). The shell wires the real trigger.
    private val resyncAfterApply: suspend () -> Unit = {},
    private val newId: () -> String = { Uuid.random().toString() },
    private val now: () -> Instant = { Clock.System.now() },
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : AssistantComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = componentScope(coroutineContext)

    private val _state = MutableStateFlow(AssistantState())
    override val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** Which Conversation is open — the driver for the message Flow (null = a fresh, unsaved chat). */
    private val activeId = MutableStateFlow<ConversationId?>(null)

    /** The in-flight turn's collection job, so [onCancelTurn] can stop it. */
    private var turnJob: Job? = null

    init {
        // The switcher list + the active Conversation's log come from the local cache (offline-readable;
        // streamed upserts and hydration backfill both flow in here).
        scope.launch {
            store.observeConversations().collect { convos -> _state.update { it.copy(conversations = convos) } }
        }
        scope.launch {
            activeId
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store.observeMessages(id) }
                .collect { msgs -> _state.update { it.copy(messages = msgs) } }
        }
        scope.launch {
            connectivity.online.collect { online -> _state.update { it.copy(online = online) } }
        }
        // The availability gate is a shared StateFlow (the shell also feeds it to the Settings row), so the
        // chat reflects an enable/disable made from either surface — offline/failed simply leaves it null.
        scope.launch {
            availability.collect { gate -> _state.update { it.copy(availability = gate) } }
        }
        scope.launch { syncConversationList() }
    }

    override fun onComposerChanged(text: String) {
        _state.update { it.copy(composer = text) }
    }

    override fun onSend() {
        val snapshot = _state.value
        if (!snapshot.canSend) return
        val text = snapshot.composer.trim()
        val conversationId = snapshot.activeConversationId ?: newConversation()
        val startedAt = now()
        val userMessage = ChatMessage(id = newId(), role = ChatRole.User, text = text, createdAt = startedAt)
        val assistantMessageId = newId()

        _state.update { it.copy(composer = "", streaming = true, error = null, actions = emptyList()) }

        turnJob = scope.launch {
            // Preserve an existing Conversation's title; only a brand-new chat gets one from its first line.
            val existingTitle = store.observeConversations().first().firstOrNull { it.id == conversationId }?.title
            val title = existingTitle ?: titleFrom(text)
            store.upsertConversation(Conversation(conversationId, title, startedAt))
            store.upsertMessage(conversationId, userMessage)

            var buffer = ""
            try {
                stream.streamTurn(AssistantTurnRequest(orgId, conversationId, text)).collect { event ->
                    when (event) {
                        is AssistantEvent.TextDelta -> {
                            buffer += event.text
                            store.upsertMessage(
                                conversationId,
                                ChatMessage(assistantMessageId, ChatRole.Assistant, buffer, now()),
                            )
                        }
                        is AssistantEvent.Proposal -> _state.update { it.copy(pendingProposal = event.proposal) }
                        // Symmetric: a later usage frame after a reset clears the hard-stop, never just sets it.
                        is AssistantEvent.Usage -> _state.update { it.copy(usageExhausted = event.exhausted) }
                        is AssistantEvent.Error -> _state.update { it.copy(error = event.message) }
                        // Each autonomous tool action shows as transient activity (cleared next turn) — the
                        // person sees what the Assistant did, not just its final text. A result completes a
                        // call it already named, so it adds no new line.
                        is AssistantEvent.ToolCall ->
                            _state.update { it.copy(actions = it.actions + event.tool.ifBlank { "Working…" }) }
                        is AssistantEvent.ToolResult -> Unit
                        AssistantEvent.Done -> store.upsertConversation(Conversation(conversationId, title, now()))
                    }
                }
            } catch (e: CancellationException) {
                throw e // a cancel (onCancelTurn) is not an error
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "The turn failed. Try again.") }
            } finally {
                // Synchronous state update — safe even after cancellation; the partial reply stays persisted.
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    override fun onCancelTurn() {
        turnJob?.cancel()
    }

    override fun onNewConversation() {
        activeId.value = null
        _state.update { it.copy(activeConversationId = null, pendingProposal = null, error = null, actions = emptyList()) }
    }

    override fun onSelectConversation(id: ConversationId) {
        activeId.value = id
        _state.update { it.copy(activeConversationId = id, pendingProposal = null, error = null, actions = emptyList()) }
        scope.launch { hydrate(id) }
    }

    override fun onEnableRequested() {
        _state.update { it.copy(showingDisclosure = true) }
    }

    override fun onConsentDeclined() {
        _state.update { it.copy(showingDisclosure = false) }
    }

    override fun onConsentAccepted() {
        if (_state.value.enabling) return
        _state.update { it.copy(enabling = true, error = null) }
        scope.launch {
            // The write-through updates the shared [availability] flow on success (so the gate reflects
            // everywhere at once); we only own the enabling/disclosure UI state + the failure message.
            val updated = setEnabled(true)
            _state.update {
                if (updated != null) it.copy(enabling = false, showingDisclosure = false)
                else it.copy(enabling = false, error = "Couldn't enable the Assistant. Try again.")
            }
        }
    }

    override fun onConfirmProposal() {
        val proposal = _state.value.pendingProposal ?: return
        if (_state.value.applyingProposal) return
        _state.update { it.copy(applyingProposal = true) }
        scope.launch {
            when (val result = client.apply(orgId, proposal)) {
                is RemoteSnapshot.Available -> {
                    if (result.value) resyncAfterApply()
                    _state.value.activeConversationId?.let { conversationId ->
                        val outcome = if (result.value) "Applied: ${proposal.summary}" else "Couldn't apply: ${proposal.summary}"
                        store.upsertMessage(
                            conversationId,
                            ChatMessage(newId(), ChatRole.Assistant, outcome, now()),
                        )
                    }
                    _state.update { it.copy(pendingProposal = null, applyingProposal = false) }
                }
                is RemoteSnapshot.Unavailable ->
                    _state.update { it.copy(applyingProposal = false, error = "Couldn't apply the change. Try again.") }
            }
        }
    }

    override fun onRejectProposal() {
        _state.update { it.copy(pendingProposal = null) }
    }

    override fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun newConversation(): ConversationId {
        val id = ConversationId(newId())
        activeId.value = id
        _state.update { it.copy(activeConversationId = id) }
        return id
    }

    /** Cross-device: make server-listed Conversations (incl. web-started) selectable by seeding placeholders. */
    private suspend fun syncConversationList() {
        val result = client.conversations(orgId)
        if (result !is RemoteSnapshot.Available) return
        val cached = store.observeConversations().first().map { it.id }.toSet()
        result.value.filter { it !in cached }.forEach { id ->
            store.upsertConversation(Conversation(id, title = null, updatedAt = Instant.DISTANT_PAST))
        }
    }

    /** Local-truth + server backfill: the cache already renders; merge any messages it was missing. */
    private suspend fun hydrate(id: ConversationId) {
        when (val result = client.conversation(orgId, id)) {
            is RemoteSnapshot.Available -> {
                store.upsertConversation(result.value.conversation)
                store.upsertMessages(id, result.value.messages)
            }
            is RemoteSnapshot.Unavailable -> Unit // offline/failed: the local cache is the source of truth
        }
    }

    private fun titleFrom(message: String): String =
        message.lineSequence().firstOrNull()?.trim()?.take(50)?.ifBlank { null } ?: "New chat"
}

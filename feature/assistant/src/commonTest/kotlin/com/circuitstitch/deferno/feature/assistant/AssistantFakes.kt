package com.circuitstitch.deferno.feature.assistant

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationDetail
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Test doubles for the three [DefaultAssistantComponent] seams (#282) — all in-memory and deterministic
 * (StateFlow-backed + simple recorders), so the chat state machine is driven with no real network, SSE, or
 * SQLite. Prior art: the core agent's fake `InferenceEngine` and the feature slices' fake repositories.
 */

/** A scripted [AssistantStream]: enqueue an event sequence, optionally parking at the tail (for cancel). */
class FakeAssistantStream : AssistantStream {
    val requests = mutableListOf<AssistantTurnRequest>()
    private var events: List<AssistantEvent> = emptyList()
    private var parkAtTail = false

    fun script(vararg events: AssistantEvent, thenSuspend: Boolean = false) {
        this.events = events.toList()
        parkAtTail = thenSuspend
    }

    override fun streamTurn(request: AssistantTurnRequest): Flow<AssistantEvent> = flow {
        requests += request
        events.forEach { emit(it) }
        if (parkAtTail) awaitCancellation()
    }
}

/**
 * A programmable [AssistantClient]: set each endpoint's [RemoteSnapshot]; recorders capture the calls.
 * Availability + enablement are owned by the shared source + write-through (the [FakeEnablement] seam) now,
 * not this client, so the component never calls those two — they stay benign stubs to satisfy the interface.
 */
class FakeAssistantClient : AssistantClient {
    var applyResult: RemoteSnapshot<Boolean> = RemoteSnapshot.Available(true)
    var conversationsResult: RemoteSnapshot<List<ConversationId>> = RemoteSnapshot.Available(emptyList())
    var conversationDetail: (ConversationId) -> RemoteSnapshot<ConversationDetail> = { RemoteSnapshot.Unavailable }

    val appliedProposals = mutableListOf<AssistantProposal>()

    override suspend fun availability(orgId: OrgId): RemoteSnapshot<AssistantAvailability> = RemoteSnapshot.Unavailable

    override suspend fun setEnablement(orgId: OrgId, enabled: Boolean): RemoteSnapshot<AssistantAvailability> =
        RemoteSnapshot.Unavailable

    override suspend fun apply(orgId: OrgId, proposal: AssistantProposal): RemoteSnapshot<Boolean> {
        appliedProposals += proposal
        return applyResult
    }

    override suspend fun conversations(orgId: OrgId) = conversationsResult

    override suspend fun conversation(orgId: OrgId, id: ConversationId) = conversationDetail(id)
}

/** An in-memory [ConversationStore] (StateFlow-backed) recording every upserted message. */
class FakeConversationStore : ConversationStore {
    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val messages = MutableStateFlow<Map<ConversationId, List<ChatMessage>>>(emptyMap())

    /** Every (conversation, message) upsert in order — for asserting stream-time persistence. */
    val upserts = mutableListOf<Pair<ConversationId, ChatMessage>>()

    override fun observeConversations(): Flow<List<Conversation>> =
        conversations.map { list -> list.sortedByDescending { it.updatedAt } }

    override fun observeMessages(id: ConversationId): Flow<List<ChatMessage>> =
        messages.map { it[id].orEmpty() }

    override suspend fun upsertConversation(conversation: Conversation) {
        conversations.update { list -> list.filterNot { it.id == conversation.id } + conversation }
    }

    override suspend fun upsertMessage(conversationId: ConversationId, message: ChatMessage) {
        upserts += conversationId to message
        messages.update { map ->
            val log = map[conversationId].orEmpty().filterNot { it.id == message.id } + message
            map + (conversationId to log)
        }
    }

    override suspend fun upsertMessages(conversationId: ConversationId, messages: List<ChatMessage>) {
        messages.forEach { upsertMessage(conversationId, it) }
    }
}

/** A [Connectivity] whose online state can be flipped mid-test. */
class FakeConnectivity(initial: Boolean = true) : Connectivity {
    private val _online = MutableStateFlow(initial)
    override val online: StateFlow<Boolean> get() = _online
    fun setOnline(value: Boolean) { _online.value = value }
}

/**
 * The shell's shared availability source + enablement write-through (ADR-0040), faked: [availability] is the
 * flow the component observes, [setEnabled] records each flip and writes the new gate back into it (so the
 * observe loop adopts it) — unless [fail] forces a no-op failure (the gate is left unchanged).
 */
class FakeEnablement(
    val availability: MutableStateFlow<AssistantAvailability?> =
        MutableStateFlow(AssistantAvailability(entitled = true, enabled = true)),
    private val fail: Boolean = false,
) {
    val calls = mutableListOf<Boolean>()

    suspend fun setEnabled(enabled: Boolean): AssistantAvailability? {
        calls += enabled
        if (fail) return null
        val base = availability.value ?: AssistantAvailability(entitled = true, enabled = enabled)
        return base.copy(enabled = enabled).also { availability.value = it }
    }
}

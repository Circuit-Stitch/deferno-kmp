package com.circuitstitch.deferno.core.data.assistant

import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * The on-device [[Conversation]] cache (issue #282, ADR-0040): the source of truth for readable Assistant
 * history on iOS. The component persists each Conversation + its messages **as they stream**, observes
 * them back as Flows (so past chats and the switcher read offline, ADR-0001 observe-via-Flow), and
 * backfills any messages a server hydration ([upsertMessages]) returns that the cache is missing.
 *
 * Online-only to *extend* a Conversation (ADR-0040): turns are never outbox-queued, so there is no
 * write/reconcile seam here — just upsert (stream + hydrate) and observe.
 */
interface ConversationStore {

    /** The switcher list — every cached Conversation, most-recently-updated first. */
    fun observeConversations(): Flow<List<Conversation>>

    /** One Conversation's ordered message log. */
    fun observeMessages(id: ConversationId): Flow<List<ChatMessage>>

    /** Upsert a Conversation row (created on first turn, touched as it grows). */
    suspend fun upsertConversation(conversation: Conversation)

    /** Upsert one message — by id, so a streaming reply accrues in place. */
    suspend fun upsertMessage(conversationId: ConversationId, message: ChatMessage)

    /** Bulk upsert (one transaction) — the cross-device hydration backfill path. */
    suspend fun upsertMessages(conversationId: ConversationId, messages: List<ChatMessage>)
}

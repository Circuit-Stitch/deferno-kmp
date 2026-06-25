package com.circuitstitch.deferno.core.data.assistant

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.ConversationEntity
import com.circuitstitch.deferno.core.database.sql.ConversationMessageEntity
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.ChatRole
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * The SQLDelight [ConversationStore] over the per-Account [DefernoDatabase] (#282, ADR-0040/0022). Local-
 * only — no remote source or reconcile (the Assistant is online-only to *extend*, ADR-0040). Reads are
 * observe-via-Flow (ADR-0001); writes upsert by id so a streaming reply accrues in one row. Both tables
 * live in `ConversationEntity.sq`, so every query is on the single `conversationEntityQueries`.
 */
class SqlDelightConversationStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConversationStore {

    private val queries get() = db.conversationEntityQueries

    override fun observeConversations(): Flow<List<Conversation>> =
        queries.selectAllConversations().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override fun observeMessages(id: ConversationId): Flow<List<ChatMessage>> =
        queries.selectMessages(id.value).asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertConversation(conversation: Conversation) {
        queries.upsertConversation(
            id = conversation.id.value,
            title = conversation.title,
            updated_at = conversation.updatedAt.toString(),
        )
    }

    override suspend fun upsertMessage(conversationId: ConversationId, message: ChatMessage) {
        queries.upsertMessage(
            id = message.id,
            conversation_id = conversationId.value,
            role = message.role.name,
            text = message.text,
            created_at = message.createdAt.toString(),
        )
    }

    override suspend fun upsertMessages(conversationId: ConversationId, messages: List<ChatMessage>) {
        queries.transaction {
            messages.forEach { message ->
                queries.upsertMessage(
                    id = message.id,
                    conversation_id = conversationId.value,
                    role = message.role.name,
                    text = message.text,
                    created_at = message.createdAt.toString(),
                )
            }
        }
    }
}

private fun ConversationEntity.toDomain(): Conversation =
    Conversation(id = ConversationId(id), title = title, updatedAt = Instant.parse(updated_at))

private fun ConversationMessageEntity.toDomain(): ChatMessage =
    ChatMessage(id = id, role = role.toChatRole(), text = text, createdAt = Instant.parse(created_at))

// Defensive: an unknown/legacy role string degrades to Assistant rather than throwing (never enumValueOf).
private fun String.toChatRole(): ChatRole =
    ChatRole.entries.firstOrNull { it.name == this } ?: ChatRole.Assistant

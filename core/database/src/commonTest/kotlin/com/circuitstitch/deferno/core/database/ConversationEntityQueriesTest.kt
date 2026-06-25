package com.circuitstitch.deferno.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the Assistant Conversation cache schema + queries (#282, ADR-0040): the switcher list ordered
 * most-recent-first, the per-conversation message log ordered chronologically, streaming upsert-by-id
 * accrual, and scoped deletes. Run against in-memory SQLite (ADR-0006 JVM-fast path).
 *
 * Both tables live in `ConversationEntity.sq`, so SQLDelight exposes every query on the single
 * file-named `conversationEntityQueries`.
 */
class ConversationEntityQueriesTest {

    @Test
    fun conversationsAreListedMostRecentlyUpdatedFirst() {
        val q = inMemoryDefernoDatabase().conversationEntityQueries
        q.upsertConversation("c-old", "Old", "2026-06-20T10:00:00Z")
        q.upsertConversation("c-new", "New", "2026-06-24T10:00:00Z")
        q.upsertConversation("c-mid", null, "2026-06-22T10:00:00Z")

        val ids = q.selectAllConversations().executeAsList().map { it.id }
        assertEquals(listOf("c-new", "c-mid", "c-old"), ids)
    }

    @Test
    fun messagesAreScopedToTheirConversationAndOrderedChronologically() {
        val q = inMemoryDefernoDatabase().conversationEntityQueries
        q.upsertMessage("m2", "c-1", "assistant", "hi", "2026-06-24T10:00:05Z")
        q.upsertMessage("m1", "c-1", "user", "hello", "2026-06-24T10:00:00Z")
        q.upsertMessage("x1", "c-2", "user", "other", "2026-06-24T10:00:01Z")

        val log = q.selectMessages("c-1").executeAsList()
        assertEquals(listOf("m1", "m2"), log.map { it.id })
        assertEquals(listOf("hello", "hi"), log.map { it.text })
    }

    @Test
    fun upsertByIdAccruesAStreamingReplyInPlace() {
        val q = inMemoryDefernoDatabase().conversationEntityQueries
        q.upsertMessage("m1", "c-1", "assistant", "Hel", "2026-06-24T10:00:00Z")
        q.upsertMessage("m1", "c-1", "assistant", "Hello", "2026-06-24T10:00:00Z")

        val log = q.selectMessages("c-1").executeAsList()
        assertEquals(1, log.size)
        assertEquals("Hello", log.single().text)
    }

    @Test
    fun deletingAConversationCanAlsoClearItsMessages() {
        val q = inMemoryDefernoDatabase().conversationEntityQueries
        q.upsertConversation("c-1", "Keep", "2026-06-24T10:00:00Z")
        q.upsertMessage("m1", "c-1", "user", "hi", "2026-06-24T10:00:00Z")

        q.deleteConversation("c-1")
        q.deleteMessagesForConversation("c-1")

        assertTrue(q.selectAllConversations().executeAsList().isEmpty())
        assertTrue(q.selectMessages("c-1").executeAsList().isEmpty())
    }
}

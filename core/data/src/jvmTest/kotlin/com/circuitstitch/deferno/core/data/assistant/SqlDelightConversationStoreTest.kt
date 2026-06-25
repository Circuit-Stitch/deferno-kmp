package com.circuitstitch.deferno.core.data.assistant

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.ChatRole
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Behaviour of [SqlDelightConversationStore] (#282, ADR-0040) against a genuine [DefernoDatabase] over an
 * in-memory driver (ADR-0006 JVM-fast path). Proves stream-time persistence (upsert-by-id accrual), the
 * offline-readable observed log + switcher list, and the bulk hydration backfill.
 */
class SqlDelightConversationStoreTest {

    private val cid = ConversationId("c-1")

    private fun store() = SqlDelightConversationStore(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
    )

    private fun msg(id: String, role: ChatRole, text: String, at: String) =
        ChatMessage(id = id, role = role, text = text, createdAt = Instant.parse(at))

    @Test
    fun aStreamingReplyAccruesInPlaceAndReadsBackOffline() = runTest {
        val store = store()
        store.observeMessages(cid).test {
            assertEquals(emptyList(), awaitItem())

            store.upsertMessage(cid, msg("u1", ChatRole.User, "hello", "2026-06-24T10:00:00Z"))
            assertEquals(listOf("hello"), awaitItem().map { it.text })

            // The assistant reply is one row that grows token-by-token (same id).
            store.upsertMessage(cid, msg("a1", ChatRole.Assistant, "Hel", "2026-06-24T10:00:05Z"))
            assertEquals(listOf("hello", "Hel"), awaitItem().map { it.text })
            store.upsertMessage(cid, msg("a1", ChatRole.Assistant, "Hello", "2026-06-24T10:00:05Z"))
            val log = awaitItem()
            assertEquals(listOf("hello", "Hello"), log.map { it.text })
            assertEquals(listOf(ChatRole.User, ChatRole.Assistant), log.map { it.role })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun conversationsAreObservedMostRecentFirst() = runTest {
        val store = store()
        store.observeConversations().test {
            assertEquals(emptyList(), awaitItem())
            store.upsertConversation(Conversation(ConversationId("c-old"), "Old", Instant.parse("2026-06-20T10:00:00Z")))
            awaitItem()
            store.upsertConversation(Conversation(ConversationId("c-new"), "New", Instant.parse("2026-06-24T10:00:00Z")))
            assertEquals(listOf("c-new", "c-old"), awaitItem().map { it.id.value })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun bulkHydrationBackfillsMissingMessages() = runTest {
        val store = store()
        store.upsertMessage(cid, msg("u1", ChatRole.User, "local", "2026-06-24T10:00:00Z"))

        // A server hydration returns the local message plus one the cache was missing.
        store.upsertMessages(
            cid,
            listOf(
                msg("u1", ChatRole.User, "local", "2026-06-24T10:00:00Z"),
                msg("a1", ChatRole.Assistant, "from web", "2026-06-24T10:00:10Z"),
            ),
        )

        store.observeMessages(cid).test {
            assertEquals(listOf("local", "from web"), awaitItem().map { it.text })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

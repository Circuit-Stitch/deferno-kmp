package com.circuitstitch.deferno.core.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cached item-history store (ADR-0043, #197): proves the `itemHistoryEntry` raw-row CRUD over a
 * real in-memory `DefernoDatabase` — the per-item feed is oldest-first, and the on-open reconcile
 * REPLACES an item's rows wholesale (append-only server history has no stable event id to merge on).
 * Serialization/mapping of the `payload` is the repository's job; this store is network-free.
 */
class ItemHistoryLocalStoreTest {

    private fun newStore(): ItemHistoryLocalStore = SqlDelightItemHistoryLocalStore(
        DefernoDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) }),
        Dispatchers.Unconfined,
    )

    private fun row(at: String, payload: String) = StoredHistoryRow(recordedAt = at, payload = payload)

    @Test
    fun replaceForItemStoresRowsObservedOldestFirst() = runTest {
        val store = newStore()

        store.replaceForItem(
            "item-1",
            listOf(
                row("2026-06-21T12:00:02Z", "\"MergedIntoParent\""),
                row("2026-06-21T12:00:00Z", "\"Created\""),
            ),
        )

        val feed = store.observe("item-1").first()
        assertEquals(listOf("2026-06-21T12:00:00Z", "2026-06-21T12:00:02Z"), feed.map { it.recordedAt })
        assertEquals(listOf("\"Created\"", "\"MergedIntoParent\""), feed.map { it.payload })
    }

    @Test
    fun replaceForItemReplacesTheItemsPriorRowsWholesale() = runTest {
        val store = newStore()
        store.replaceForItem("item-1", listOf(row("2026-06-21T12:00:00Z", "\"Created\"")))

        store.replaceForItem("item-1", listOf(row("2026-06-21T13:00:00Z", "\"MergedIntoParent\"")))

        assertEquals(listOf("\"MergedIntoParent\""), store.observe("item-1").first().map { it.payload })
    }

    @Test
    fun replaceForItemIsScopedToTheItem() = runTest {
        val store = newStore()
        store.replaceForItem("item-1", listOf(row("2026-06-21T12:00:00Z", "\"Created\"")))
        store.replaceForItem("item-2", listOf(row("2026-06-21T12:00:00Z", "\"Created\"")))

        store.replaceForItem("item-1", emptyList())

        assertEquals(emptyList(), store.observe("item-1").first())
        assertEquals(1, store.observe("item-2").first().size)
    }
}

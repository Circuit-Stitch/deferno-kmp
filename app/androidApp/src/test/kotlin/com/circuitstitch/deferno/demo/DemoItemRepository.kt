package com.circuitstitch.deferno.demo

import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.model.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ItemRepository] **test fake** for the shell + Compose-View tests (#227, ADR-0034). Reads are
 * a [MutableStateFlow]; [refresh] is a no-op (the sample data is the local source of truth). The real app
 * uses the DI-provided OfflineItemRepository (#226, ADR-0014); this stays a test fixture.
 */
internal class DemoItemRepository(initial: List<Item> = emptyList()) : ItemRepository {
    private val items = MutableStateFlow(initial)
    override fun observeItems(): Flow<List<Item>> = items
    override suspend fun refresh() { /* offline demo: nothing to pull */ }
}

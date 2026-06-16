package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.data.item.ItemRepository
import com.circuitstitch.deferno.core.model.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ItemRepository] for component tests. Reads are a [MutableStateFlow] the tests mutate;
 * `refresh()` records its call count, mirroring the offline-first contract (reads are local Flows; a
 * network pull writes through — ADR-0001).
 */
class FakeItemRepository(initial: List<Item> = emptyList()) : ItemRepository {
    val items = MutableStateFlow(initial)

    var refreshCount = 0
        private set

    override fun observeItems(): Flow<List<Item>> = items

    override suspend fun refresh() {
        refreshCount++
    }
}

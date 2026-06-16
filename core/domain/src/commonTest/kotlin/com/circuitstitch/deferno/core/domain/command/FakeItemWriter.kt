package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.item.ItemWriter

/**
 * Call-recording [ItemWriter] for the command-registry tests — the cross-kind move counterpart to
 * [FakeTaskWriter]. Records each [move] so a test can assert the executor routed [MoveItem] here with
 * the right id / destination / index.
 */
class FakeItemWriter : ItemWriter {
    val calls = mutableListOf<Call>()

    data class Call(val id: String, val newParentId: String?, val position: Int)

    override suspend fun move(id: String, newParentId: String?, position: Int) {
        calls += Call(id, newParentId, position)
    }
}

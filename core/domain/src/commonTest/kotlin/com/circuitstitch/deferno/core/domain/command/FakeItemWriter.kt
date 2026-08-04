package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.item.ItemWriter

/**
 * Call-recording [ItemWriter] for the command-registry tests — the cross-kind counterpart to
 * [FakeTaskWriter]. Records each write so a test can assert the executor routed [MoveItem] /
 * [DeleteItem] here with the right operands.
 *
 * [Call.Delete] records **only an id**, mirroring the seam: `ItemWriter.delete` takes no [ItemKind]
 * because the server route resolves the kind itself and deletes the whole Series chain (#389). A fake
 * that recorded a kind would let a test "pass" while pinning an operand production never sends.
 */
class FakeItemWriter : ItemWriter {
    val calls = mutableListOf<Call>()

    sealed interface Call {
        data class Move(val id: String, val newParentId: String?, val position: Int) : Call
        data class Delete(val id: String) : Call
    }

    override suspend fun move(id: String, newParentId: String?, position: Int) {
        calls += Call.Move(id, newParentId, position)
    }

    override suspend fun delete(id: String) {
        calls += Call.Delete(id)
    }
}

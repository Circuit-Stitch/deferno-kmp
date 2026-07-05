package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.task.BlockedByResult
import com.circuitstitch.deferno.core.data.task.BlockedByWriter
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.TaskId

/** Recording [BlockedByWriter] fake (#291): captures each call and returns the scripted [result]. */
class FakeBlockedByWriter(
    var result: BlockedByResult = BlockedByResult.Applied,
) : BlockedByWriter {
    val calls = mutableListOf<Call>()

    data class Call(val id: TaskId, val blockers: List<BlockedByRef>)

    override suspend fun setBlockedBy(id: TaskId, blockers: List<BlockedByRef>): BlockedByResult {
        calls += Call(id, blockers)
        return result
    }
}

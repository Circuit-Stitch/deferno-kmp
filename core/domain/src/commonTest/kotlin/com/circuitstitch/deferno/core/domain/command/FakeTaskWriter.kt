package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.task.TaskWriter
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * Call-recording [TaskWriter] for the command-registry tests: every method appends a typed [Call]
 * record (mirrors the `core:data` recording-fake idiom), so a test can assert that
 * [CommandExecutor.execute] routed a command to exactly the right writer method with the exact
 * operands — the intent → action table.
 */
class FakeTaskWriter : TaskWriter {
    val calls = mutableListOf<Call>()

    sealed interface Call {
        data class SetWorkingState(val id: TaskId, val state: WorkingState) : Call
        data class Rename(val id: TaskId, val title: String) : Call
        data class SetDeadline(val id: TaskId, val completeBy: Instant) : Call
        data class ClearDeadline(val id: TaskId) : Call
        data class SetDeadlineTime(val id: TaskId, val timeOfDay: LocalTime?) : Call
        data class SetDescription(val id: TaskId, val description: String) : Call
        data class ClearDescription(val id: TaskId) : Call
        data class SetLabels(val id: TaskId, val labels: List<String>) : Call
        data class SetPinned(val id: TaskId, val pinned: Boolean) : Call
        data class Delete(val id: TaskId) : Call
    }

    override suspend fun setWorkingState(id: TaskId, state: WorkingState) { calls += Call.SetWorkingState(id, state) }
    override suspend fun rename(id: TaskId, title: String) { calls += Call.Rename(id, title) }
    override suspend fun setDeadline(id: TaskId, completeBy: Instant) { calls += Call.SetDeadline(id, completeBy) }
    override suspend fun clearDeadline(id: TaskId) { calls += Call.ClearDeadline(id) }
    override suspend fun setDeadlineTime(id: TaskId, timeOfDay: LocalTime?) { calls += Call.SetDeadlineTime(id, timeOfDay) }
    override suspend fun setDescription(id: TaskId, description: String) { calls += Call.SetDescription(id, description) }
    override suspend fun clearDescription(id: TaskId) { calls += Call.ClearDescription(id) }
    override suspend fun setLabels(id: TaskId, labels: List<String>) { calls += Call.SetLabels(id, labels) }
    override suspend fun setPinned(id: TaskId, pinned: Boolean) { calls += Call.SetPinned(id, pinned) }
    override suspend fun delete(id: TaskId) { calls += Call.Delete(id) }
}

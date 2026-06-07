package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.plan.PlanWriter
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Call-recording [PlanWriter] for the command-registry tests — the plan counterpart to [FakeTaskWriter].
 */
class FakePlanWriter : PlanWriter {
    val calls = mutableListOf<Call>()

    sealed interface Call {
        data class Add(val taskId: TaskId, val date: LocalDate, val tz: String) : Call
        data class Remove(val taskId: TaskId, val date: LocalDate, val tz: String) : Call
        data class Reorder(val taskIds: List<TaskId>, val date: LocalDate, val tz: String) : Call
    }

    override suspend fun add(taskId: TaskId, date: LocalDate, tz: String) { calls += Call.Add(taskId, date, tz) }
    override suspend fun remove(taskId: TaskId, date: LocalDate, tz: String) { calls += Call.Remove(taskId, date, tz) }
    override suspend fun reorder(taskIds: List<TaskId>, date: LocalDate, tz: String) { calls += Call.Reorder(taskIds, date, tz) }
}

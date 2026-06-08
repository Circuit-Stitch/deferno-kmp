package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.create.CreateResult
import com.circuitstitch.deferno.core.data.create.CreateWriter
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload

/**
 * Call-recording [CreateWriter] for the command-registry tests. Returns a configurable [result] (so a
 * test can drive the online/offline/failed arms) and records each call, so a test can assert the
 * online-only command routed to the writer with the right kind — and, crucially, that an offline
 * command **still called the writer** (the writer owns the connectivity gate, ADR-0016) while the
 * executor faithfully surfaces the [CreateResult.Offline] the writer reports.
 */
class FakeCreateWriter(
    var result: CreateResult = CreateResult.Created(ItemKind.Task, "server-1"),
) : CreateWriter {
    val calls = mutableListOf<String>()

    override suspend fun createTask(payload: CreateTaskPayload): CreateResult {
        calls += "createTask"; return result
    }

    override suspend fun createHabit(payload: CreateHabitPayload): CreateResult {
        calls += "createHabit"; return result
    }

    override suspend fun createChore(payload: CreateChorePayload): CreateResult {
        calls += "createChore"; return result
    }

    override suspend fun createEvent(payload: CreateEventPayload): CreateResult {
        calls += "createEvent"; return result
    }

    override suspend fun convert(id: String, fromKind: ItemKind, payload: ConvertItemPayload): CreateResult {
        calls += "convert:$id:$fromKind"; return result
    }
}

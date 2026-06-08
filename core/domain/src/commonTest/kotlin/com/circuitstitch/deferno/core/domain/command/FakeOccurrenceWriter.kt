package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.calendar.OccurrenceWriter
import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.datetime.LocalDate

/**
 * Call-recording [OccurrenceWriter] for the command-registry tests (#74) — the firing-level sibling of
 * [FakeTaskWriter]. Records each occurrence act so a test can assert [CommandExecutor] routed the
 * Mark/Clear/Reschedule command to the writer with the right operands, and that a non-occurrence
 * command never touched it.
 */
class FakeOccurrenceWriter : OccurrenceWriter {
    val calls = mutableListOf<Call>()

    override suspend fun mark(itemId: String, action: OccurrenceAction) { calls += Call.Mark(itemId, action) }
    override suspend fun clear(itemId: String) { calls += Call.Clear(itemId) }
    override suspend fun reschedule(itemId: String, newDate: LocalDate) { calls += Call.Reschedule(itemId, newDate) }

    sealed interface Call {
        data class Mark(val itemId: String, val action: OccurrenceAction) : Call
        data class Clear(val itemId: String) : Call
        data class Reschedule(val itemId: String, val newDate: LocalDate) : Call
    }
}

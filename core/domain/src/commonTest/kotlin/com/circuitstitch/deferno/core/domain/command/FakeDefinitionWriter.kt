package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.definition.DefinitionWriter
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import kotlin.time.Instant

/**
 * Call-recording [DefinitionWriter] for the command-registry tests — the recurring-definition counterpart
 * to [FakeTaskWriter]. Records each write so a test can assert the executor routed the definition command
 * here with the right id / kind / operand.
 *
 * The seam grew from one verb to three (#378), so [Call] is a **sealed hierarchy** (the [FakeTaskWriter] /
 * [FakeOccurrenceWriter] idiom) rather than the single flat record it was under #299. A flat record would
 * have had to carry all three operands nullable, and then a test asserting "set the priority" could not
 * distinguish a `setPriority` call from a `setTargetDate(null)` one that happened to leave priority
 * defaulted — which is precisely the mis-routing this fake exists to catch.
 */
class FakeDefinitionWriter : DefinitionWriter {
    val calls = mutableListOf<Call>()

    sealed interface Call {
        data class SetState(val id: String, val kind: ItemKind, val target: DefinitionState) : Call
        data class SetTargetDate(val id: String, val kind: ItemKind, val targetDate: Instant?) : Call
        data class SetPriority(val id: String, val kind: ItemKind, val priority: Priority) : Call
    }

    override suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState) {
        calls += Call.SetState(id, kind, target)
    }

    override suspend fun setTargetDate(id: String, kind: ItemKind, targetDate: Instant?) {
        calls += Call.SetTargetDate(id, kind, targetDate)
    }

    override suspend fun setPriority(id: String, kind: ItemKind, priority: Priority) {
        calls += Call.SetPriority(id, kind, priority)
    }
}

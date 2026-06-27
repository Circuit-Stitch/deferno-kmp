package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.definition.DefinitionWriter
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind

/**
 * Call-recording [DefinitionWriter] for the command-registry tests — the recurring-definition counterpart
 * to [FakeTaskWriter]. Records each [setDefinitionState] so a test can assert the executor routed
 * [SetDefinitionState] here with the right id / kind / target.
 */
class FakeDefinitionWriter : DefinitionWriter {
    val calls = mutableListOf<Call>()

    data class Call(val id: String, val kind: ItemKind, val target: DefinitionState)

    override suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState) {
        calls += Call(id, kind, target)
    }
}

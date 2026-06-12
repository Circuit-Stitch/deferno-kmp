package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The test schema the seam suites round-trip — shaped like the [[Extractor]]'s eventual output
 * (nested list + nullable + defaulted fields), so the walking skeleton proves the kind of structure
 * #148 will actually ask for.
 */
@Serializable
@SerialName("draft_tasks")
data class DraftTasks(val drafts: List<Draft>)

@Serializable
data class Draft(
    val title: String,
    val completeBy: String? = null,
    val productive: Boolean = false,
)

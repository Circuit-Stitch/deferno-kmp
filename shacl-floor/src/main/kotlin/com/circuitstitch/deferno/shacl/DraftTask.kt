package com.circuitstitch.deferno.shacl

import kotlinx.serialization.Serializable

/**
 * A proposed Task the person reviews before anything is created — the Kotlin mirror of the
 * shacl-aio crate's `DraftTask`. Field names match the crate's camelCase JSON (serde
 * `rename_all = "camelCase"`); `completeBy` stays the raw RFC3339 string the floor emits and
 * is mapped to a domain `Instant` by whatever feature consumes it (deferred — see the module).
 */
@Serializable
data class DraftTask(
    val id: String = "",
    val title: String,
    val completeBy: String? = null,
    val parentId: String? = null,
    val nextTaskId: String? = null,
    val desire: Double? = null,
    val productive: Double? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

package com.circuitstitch.deferno.core.data.create

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Generates a fresh client-side Item id for an offline-first create (#185). The backend accepts
 * client-supplied UUIDs and dedupes a create on them (Kyle-Falconer/Deferno#402), so the client mints
 * the Item's canonical id up front, inserts the local row under it, and enqueues a create carrying it.
 *
 * `kotlin.uuid.Uuid` from the stdlib (no new dependency); the [com.circuitstitch.deferno.core.data.create.OfflineCreateWriter]
 * takes the generator as an injected `() -> String` so a test can pin a deterministic id.
 */
@OptIn(ExperimentalUuidApi::class)
fun newItemId(): String = Uuid.random().toString()

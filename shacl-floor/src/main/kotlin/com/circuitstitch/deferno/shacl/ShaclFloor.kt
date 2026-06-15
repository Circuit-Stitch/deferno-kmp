package com.circuitstitch.deferno.shacl

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/**
 * The shacl-aio deterministic floor: turn a (spoken) transcript into draft Tasks, entirely
 * on-device with zero ML. Wraps the native crate's C ABI; only the deterministic `extract` is
 * exposed (the crate's LLM variants shell out and have no place on a phone).
 *
 * `now` is injected (the crate never reads the wall clock) so extraction stays deterministic and
 * testable; it is passed across as RFC3339 (`Instant.toString()`).
 */
@OptIn(ExperimentalTime::class)
object ShaclFloor {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(transcript: String, now: Instant): List<DraftTask> {
        val ptr = ShaclLib.INSTANCE.shacl_extract_json(transcript, now.toString()) ?: return emptyList()
        return try {
            json.decodeFromString<List<DraftTask>>(ptr.getString(0, "UTF-8"))
        } finally {
            ShaclLib.INSTANCE.shacl_string_free(ptr)
        }
    }
}

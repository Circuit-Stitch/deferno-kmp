package com.circuitstitch.deferno.core.network

import kotlinx.serialization.json.Json

/**
 * The tolerant-reader JSON configuration for the whole network boundary (ADR-0005). Configured
 * once and shared by ContentNegotiation (outbound request bodies) and the envelope decoder
 * (inbound), so the client reads and writes the wire the same way everywhere:
 *
 * - `ignoreUnknownKeys` — additive backend fields never break parsing (the core of the
 *   tolerant reader; the API adds fields without bumping the envelope version).
 * - `coerceInputValues` — a `null`/absent value for a field that has a default falls back to
 *   the default instead of throwing, so DTOs evolve safely.
 * - `explicitNulls = false` — absent fields aren't emitted as `null` on serialize; intent-shaped
 *   mutation bodies (ADR-0001) keep their omit-vs-null distinction.
 * - `isLenient = false` — strict on the *shape* of values; tolerance is about unknown keys and
 *   defaults, not malformed JSON, which must still surface as a failure.
 */
val DefernoJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = false
}

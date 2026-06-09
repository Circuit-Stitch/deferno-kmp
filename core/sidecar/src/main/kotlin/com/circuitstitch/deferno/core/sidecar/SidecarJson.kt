package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The canonical [Json] for the Sidecar [[Sidecar protocol]] — the single source of truth for how
 * frames are encoded on the wire, shared by the codec, the tests, and the golden fixtures in
 * `contracts/sidecar/`. A **tolerant reader** (ADR-0005), so a forward-compatible Helper that adds
 * fields or sends a newer error code never breaks this client:
 *
 * - `classDiscriminator = "type"` — the `"type"` key that tags each [SidecarFrame] (part of the contract);
 * - `ignoreUnknownKeys` — tolerate fields a newer Helper adds;
 * - `coerceInputValues` — an unknown enum (e.g. a new [SidecarErrorCode]) coerces to its property default;
 * - `explicitNulls = false` / `encodeDefaults = false` — omit null/default fields for compact frames
 *   (a missing `params`/`result`/`details` decodes back to null).
 */
val SidecarJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
}

/**
 * Render an opaque payload for diagnostics **without leaking its contents** (ADR-0009). The transport
 * cannot know which payloads carry privacy-critical [[Transcript]] text, so it redacts them all — only
 * presence/absence is shown. Used by the payload-bearing [SidecarFrame] / [SidecarError] `toString`s.
 */
internal fun redact(payload: JsonElement?): String = if (payload == null) "null" else "<redacted>"

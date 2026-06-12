package com.circuitstitch.deferno.core.speech

/**
 * Whether a [SpeechToText] engine can recognize **right now, on-device** (ADR-0018). Only [Available]
 * engines are eligible for selection; otherwise the [SpeechToTextSelector] falls through to the whisper
 * floor (or reports unavailable when nothing is ready). "Available" *always* means genuine on-device
 * readiness — it can never denote a cloud path (structural never-cloud, ADR-0018).
 */
sealed interface SpeechAvailability {
    /** Ready to recognize the requested locale on-device now. */
    data object Available : SpeechAvailability

    /** Not usable now, with a non-PII [reason] the UI can turn into a gentle, honest message. */
    data class Unavailable(val reason: UnavailableReason) : SpeechAvailability
}

/** Why a [SpeechToText] engine is not [SpeechAvailability.Available] (ADR-0018). Carries no PII. */
enum class UnavailableReason {
    /** The model weights are not present yet (Play Asset Delivery still settling, ADR-0019). */
    ModelMissing,

    /** The requested locale isn't supported — v1 is English-only; non-English reports this, never mis-transcribes. */
    UnsupportedLocale,

    /** No engine is registered for this platform (e.g. desktop/iOS before their engines land). */
    NoEngine,

    /** A registered engine exists but isn't ready **transiently** (initializing, device resource busy). */
    NotReady,

    /**
     * An optional native fast path that isn't present on this device — e.g. no Sidecar Helper bound at
     * the well-known socket, the **permanent, normal state** on Linux/Windows (ADR-0024). Unlike
     * [NotReady] nothing is coming: the UI says "not available on this device" (never "preparing"),
     * and the [SpeechToTextSelector] never lets it mask a real engine's reason.
     */
    NotInstalled,
}

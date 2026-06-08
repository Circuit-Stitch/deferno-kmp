package com.circuitstitch.deferno.core.speech

/**
 * Resolves the on-device path to the whisper model weights (#92, ADR-0019). The model ships as a Play
 * Asset Delivery **install-time pack** — off the base-APK budget — so it is present from first launch but
 * lives outside the APK's classic assets; this seam hides where exactly (asset-pack location vs a
 * one-time extraction to app storage) from the engine.
 *
 * Returns `null` when the model is not yet resolvable, which is why the whisper engine reports
 * [SpeechAvailability.Unavailable] with [UnavailableReason.ModelMissing] rather than crashing. The real
 * PAD-backed implementation lands in #92 L2; until then a placeholder returns `null`. Coverage-excluded
 * (device/Play-only; ADR-0006).
 */
fun interface WhisperModelLocator {
    /** Absolute filesystem path to the model file, or `null` if it is not present/resolvable yet. */
    fun modelPath(): String?
}

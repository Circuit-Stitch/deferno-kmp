package com.circuitstitch.deferno.core.speech

import javax.sound.sampled.LineUnavailableException

/**
 * A mic capture failure the OS *refused* rather than fumbled (#116): on macOS, a TCC denial of the JVM
 * app's own microphone. A subtype of [IllegalStateException] — [MicAudioSource]'s documented failure
 * contract — so a catcher that doesn't know about denials still degrades to the generic capture error;
 * [WhisperSpeechToText] catches this subtype first and surfaces the typed [SpeechError.PermissionDenied]
 * (→ the New surface's `PermissionPermanentlyDenied` note + System Settings deep-link, #120) instead of
 * the misleading "try the mic again" retry note.
 */
internal class MicPermissionDeniedException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

/**
 * Classify a [MicAudioSource] capture failure (#116): is it shaped like an OS access denial, or an
 * ordinary device problem (missing, busy, seized)? Unlike the Sidecar engine — whose Helper introspects
 * its *own* TCC for real (#120) — the whisper floor captures with the JVM app's own mic, whose TCC row
 * the Helper cannot see (grants are per-identity), so denial detection here is **inference from the
 * failure's shape**, and macOS-only: a [SecurityException] is an access refusal by contract, and a
 * [LineUnavailableException] counts only when its message reads like a denial. Off macOS (Linux/Windows,
 * where the platform gates mic access without a TCC-style per-app grant) every failure stays the generic
 * [IllegalStateException], exactly as before. Pure mapping, OS-guarded here so callers need no `os.name`
 * logic (the `SidecarPermissionSettingsLinks` pattern).
 */
internal object MicCaptureFailures {

    /** Wrap [cause] for [MicAudioSource]'s failure contract: the denial subtype on macOS, else generic. */
    fun classify(
        message: String,
        cause: Throwable,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): IllegalStateException = when {
        osName.contains("mac", ignoreCase = true) && cause.isDenialShaped() ->
            MicPermissionDeniedException(message, cause)
        else -> IllegalStateException(message, cause)
    }

    private fun Throwable.isDenialShaped(): Boolean = when (this) {
        is SecurityException -> true
        is LineUnavailableException -> DENIAL_MARKERS.any { message.orEmpty().contains(it, ignoreCase = true) }
        else -> false
    }

    /** Message shapes a denied-line open reports, as opposed to device-missing/busy ("unavailable"). */
    private val DENIAL_MARKERS = listOf("denied", "permission", "not permitted", "not authorized")
}

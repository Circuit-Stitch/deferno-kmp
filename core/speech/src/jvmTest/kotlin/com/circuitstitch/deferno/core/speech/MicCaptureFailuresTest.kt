package com.circuitstitch.deferno.core.speech

import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertSame

/**
 * The whisper-floor denial inference (#116) on the Linux fast path: a macOS capture failure shaped like
 * a TCC access denial classifies as the typed [MicPermissionDeniedException] (→ the engine's
 * [SpeechError.PermissionDenied] → the New surface's note + System Settings deep-link), while
 * device-missing/busy failures — and *every* failure off macOS — stay the generic
 * [IllegalStateException] capture error, exactly as before.
 */
class MicCaptureFailuresTest {

    private val mac = "Mac OS X"

    @Test
    fun securityExceptionOnMacOsClassifiesAsDenial() {
        val cause = SecurityException("access denied by TCC")
        val classified = MicCaptureFailures.classify("Microphone access denied", cause, osName = mac)
        assertIs<MicPermissionDeniedException>(classified)
        assertEquals("Microphone access denied", classified.message)
        assertSame(cause, classified.cause)
    }

    @Test
    fun denialShapedLineUnavailableOnMacOsClassifiesAsDenial() {
        val denialShapes = listOf(
            "Permission denied",
            "Operation not permitted",
            "app is not authorized to capture audio",
            "no permission to access the microphone",
        )
        for (message in denialShapes) {
            val classified = MicCaptureFailures.classify(
                "Microphone unavailable", LineUnavailableException(message), osName = mac,
            )
            assertIs<MicPermissionDeniedException>(classified, "expected '$message' to read as a denial")
        }
    }

    @Test
    fun busyOrMissingLineOnMacOsStaysAGenericCaptureFailure() {
        val deviceShapes = listOf(
            LineUnavailableException("line with format PCM_SIGNED not supported."),
            LineUnavailableException("requested line is already in use"),
            LineUnavailableException(), // no message at all — never guess a denial from silence
            IllegalArgumentException("No line matching interface TargetDataLine is supported."),
        )
        for (cause in deviceShapes) {
            val classified = MicCaptureFailures.classify("Microphone unavailable", cause, osName = mac)
            assertIsNot<MicPermissionDeniedException>(classified, "expected '${cause.message}' to stay generic")
            assertSame(cause, classified.cause)
        }
    }

    @Test
    fun everyFailureStaysAGenericCaptureFailureOffMacOs() {
        // Linux/Windows unchanged (#116): no TCC-style per-app grant to deep-link, so no denial typing.
        for (osName in listOf("Linux", "Windows 11", "")) {
            val classified = MicCaptureFailures.classify(
                "Microphone access denied", SecurityException("denied"), osName = osName,
            )
            assertIsNot<MicPermissionDeniedException>(classified, "expected $osName to stay generic")
        }
    }

    @Test
    fun denialIsStillAnIllegalStateExceptionForCatchersThatDoNotKnowDenials() {
        // The graceful-degradation contract: an unaware catch (IllegalStateException) still sees the
        // failure and surfaces the old SpeechError.Capture rather than crashing the flow.
        val classified = MicCaptureFailures.classify(
            "Microphone access denied", SecurityException("denied"), osName = mac,
        )
        assertIs<IllegalStateException>(classified)
    }
}

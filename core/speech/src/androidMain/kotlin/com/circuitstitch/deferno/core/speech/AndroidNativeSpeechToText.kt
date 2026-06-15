package com.circuitstitch.deferno.core.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.circuitstitch.deferno.core.common.log.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The Android **platform** on-device speech engine (#93 fast path, ADR-0018): a native, low-latency
 * alternative to the portable whisper baseline for **single-utterance dictation** (the New surface mic).
 *
 * **Structural never-cloud (ADR-0009/0018).** It uses ONLY [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 * (API 33+), which is guaranteed on-device — never the networked [SpeechRecognizer.createSpeechRecognizer].
 * Below API 33, or when no on-device recognition pack is installed, it reports [UnavailableReason.NotInstalled]
 * and the selector falls back to whisper, so no code path can reach a server recognizer. Audio is the OS
 * recognizer's own transient capture; this engine never writes, persists, uploads, or logs audio or text.
 *
 * **Single-utterance only** ([supportsContinuous] = false): the platform recognizer settles on one
 * utterance, so the continuous [[Brain dump]] ([ContinuityHint.Continuous]) still prefers the streaming
 * whisper baseline. It outranks whisper (a "fast path") but only when [availability] confirms readiness.
 *
 * Platform glue over `android.speech` — device-only, coverage-excluded (ADR-0006), like [WhisperSpeechToText].
 */
internal class AndroidNativeSpeechToText(
    private val context: Context,
) : SpeechToText {

    // Privacy (ADR-0009/0018): logs only structure — event types + lifecycle — never audio or text.
    private val log = Logger("AndroidSTT")

    override val id: SpeechEngineId = SpeechEngineId.AndroidNative

    /** A native fast path — above the whisper floor (rank 0), but eligible only when [availability] is ready. */
    override val rank: Int = 10

    /** The platform recognizer is single-utterance; the continuous Brain dump prefers whisper (ADR-0018). */
    override val supportsContinuous: Boolean = false

    override suspend fun availability(locale: String): SpeechAvailability =
        if (onDeviceAvailable()) {
            SpeechAvailability.Available
        } else {
            // Below API 33 or no on-device pack: nothing is coming on this device → NotInstalled (not NotReady).
            SpeechAvailability.Unavailable(UnavailableReason.NotInstalled)
        }

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = callbackFlow {
        if (!onDeviceAvailable()) {
            log.w { "listen: on-device recognizer unavailable (SDK=${Build.VERSION.SDK_INT})" }
            trySend(TranscriptEvent.Error(SpeechError.Unavailable))
            close()
            return@callbackFlow
        }
        // SpeechRecognizer must be created and driven on a Looper thread → the main thread (its callbacks
        // post there too). A plain main-Looper Handler keeps this off any coroutines-android dependency.
        val main = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null

        val listener = object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                partialResults.firstTranscript()?.let { trySend(TranscriptEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle) {
                log.d { "listen: final result" }
                trySend(TranscriptEvent.Final(results.firstTranscript().orEmpty()))
                close()
            }

            override fun onError(error: Int) {
                // No-speech / timeout settle as an empty Final (silence isn't a scary error); the rest map
                // to typed, non-PII causes. Network codes can't occur on the on-device recognizer.
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        log.d { "listen: no speech (code=$error)" }
                        trySend(TranscriptEvent.Final(""))
                    }
                    else -> {
                        log.w { "listen: recognizer error code=$error" }
                        trySend(TranscriptEvent.Error(error.toSpeechError()))
                    }
                }
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        main.post {
            log.d { "listen: starting on-device recognizer (locale=$locale)" }
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.setRecognitionListener(listener)
            r.startListening(recognizerIntent(locale))
            recognizer = r
        }

        awaitClose {
            // Stop + release on the same Looper that created it (caller cancelled, or a terminal result).
            main.post {
                recognizer?.run {
                    runCatching { stopListening() }
                    destroy()
                }
                recognizer = null
            }
        }
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun recognizerIntent(locale: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Belt-and-suspenders: we already use the on-device recognizer, but forbid any network path.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun Bundle.firstTranscript(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun Int.toSpeechError(): SpeechError = when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PermissionDenied
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.Capture
        else -> SpeechError.Engine
    }
}

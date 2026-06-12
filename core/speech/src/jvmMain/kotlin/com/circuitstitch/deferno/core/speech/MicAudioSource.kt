package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/**
 * Microphone capture for the desktop whisper engine (#94, ADR-0018): a [Flow] of float32 [-1,1] mono PCM
 * chunks at 16 kHz — exactly whisper's input format — read from a `javax.sound.sampled` [TargetDataLine].
 * Each engine owns its own capture (there is no shared audio seam, ADR-0018). The audio is **transient**:
 * it flows straight to recognition and is never written to disk, persisted, uploaded, or logged (ADR-0009).
 * The desktop counterpart of Android's AudioRecord-backed `MicAudioSource`. Coverage-excluded (real audio
 * hardware; ADR-0006).
 *
 * If no microphone exists, the line can't open, or it's revoked/seized mid-session, the flow fails with an
 * [IllegalStateException] so [WhisperSpeechToText] can surface a [SpeechError.Capture] — the same contract
 * the Android source uses (the OS owns the mic-permission prompt; on desktop the platform gates access).
 * On macOS, a failure shaped like a TCC access denial fails with the [MicPermissionDeniedException]
 * subtype instead ([MicCaptureFailures], #116), so the engine surfaces the typed
 * [SpeechError.PermissionDenied] and the UI offers the System Settings deep-link, not a retry.
 */
internal class MicAudioSource(
    private val sampleRate: Int = 16_000,
    /** Read granularity, ~200 ms at 16 kHz — enough audio per chunk for the VAD without lagging partials. */
    private val chunkMillis: Int = 200,
) {
    fun stream(): Flow<FloatArray> = callbackFlow {
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate.toFloat(),
            16,                 // 16-bit samples
            1,                  // mono
            BYTES_PER_SAMPLE,   // frameSize = channels * bytesPerSample
            sampleRate.toFloat(),
            false,              // little-endian — the load-bearing argument for PCM byte order
        )
        val info = DataLine.Info(TargetDataLine::class.java, format)

        val samplesPerChunk = sampleRate * chunkMillis / 1000
        val chunkBytes = samplesPerChunk * BYTES_PER_SAMPLE

        // getLine throws IllegalArgumentException when no capture line of this type exists (e.g. a headless
        // box with no mic); open throws LineUnavailableException when the device is missing/busy/denied;
        // SecurityException is an access refusal. MicCaptureFailures maps each to the engine's
        // IllegalStateException contract, denial-shaped ones on macOS to the typed subtype (#116).
        val line: TargetDataLine = try {
            (AudioSystem.getLine(info) as TargetDataLine).also { l ->
                l.open(format, chunkBytes * LINE_BUFFER_CHUNKS) // a few chunks of headroom against jitter
                l.start()
            }
        } catch (e: LineUnavailableException) {
            close(MicCaptureFailures.classify("Microphone unavailable", e))
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            close(IllegalStateException("No microphone capture line", e))
            return@callbackFlow
        } catch (e: SecurityException) {
            close(MicCaptureFailures.classify("Microphone access denied", e))
            return@callbackFlow
        }

        val buffer = ByteArray(chunkBytes)
        try {
            // read() blocks until the buffer fills or the line is stopped/closed (then it returns ≤ 0).
            while (line.isOpen) {
                val read = line.read(buffer, 0, buffer.size)
                if (read <= 0) break
                trySend(pcm16LeToFloat(buffer, read))
            }
        } catch (e: Exception) {
            close(MicCaptureFailures.classify("Microphone read failed", e))
        }

        awaitClose {
            // stop → flush → close: halt delivery, drop buffered audio, release the OS mic for other apps.
            runCatching {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Convert [byteCount] bytes of signed 16-bit little-endian PCM to float32 in [-1,1). Only the first
     * [byteCount] bytes of [bytes] are read (the final read after stop can be short).
     */
    private fun pcm16LeToFloat(bytes: ByteArray, byteCount: Int): FloatArray {
        val sampleCount = byteCount / BYTES_PER_SAMPLE
        val out = FloatArray(sampleCount)
        var byteIndex = 0
        for (i in 0 until sampleCount) {
            val lo = bytes[byteIndex].toInt() and 0xFF // low byte: mask off Kotlin's sign extension
            val hi = bytes[byteIndex + 1].toInt()      // high byte: keep the sign
            out[i] = ((hi shl 8) or lo) / 32768f
            byteIndex += 2
        }
        return out
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        const val LINE_BUFFER_CHUNKS = 8
    }
}

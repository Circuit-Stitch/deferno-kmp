// Thin JNI bridge over the whisper.cpp C API (#92, ADR-0018). It owns no policy: it initializes a
// context from a model file, runs a CPU-only, English, on-device transcription over a float32 PCM
// buffer, and returns the concatenated segment text. All capture, VAD, partial/final cadence, and the
// "audio never leaves the device" invariant are enforced by the Kotlin engine (core:speech androidMain).
//
// The JNI symbol names below MUST match the package + class of the Kotlin `external fun` declarations:
// com.circuitstitch.deferno.core.speech.WhisperBridge.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_circuitstitch_deferno_core_speech_WhisperBridge_initContext(
        JNIEnv *env, jobject /* this */, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // strictly on-device CPU recognition (ADR-0018/0009)
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_circuitstitch_deferno_core_speech_WhisperBridge_freeContext(
        JNIEnv * /* env */, jobject /* this */, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

// Transcribe a float32 [-1,1] 16 kHz mono PCM buffer; returns the concatenated segment text ("" on
// failure). Not reentrant on a single context — the Kotlin engine serializes calls per session.
extern "C" JNIEXPORT jstring JNICALL
Java_com_circuitstitch_deferno_core_speech_WhisperBridge_transcribe(
        JNIEnv *env, jobject /* this */, jlong ctx_ptr, jfloatArray audio, jint num_threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en"; // English-only v1 (ADR-0018)
    params.n_threads = num_threads > 0 ? num_threads : 4;
    params.no_context = true; // each call is independent (we re-run over accumulated audio)
    params.suppress_blank = true;

    std::string result;
    if (whisper_full(ctx, params, samples, n) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) {
                result += text;
            }
        }
    } else {
        LOGE("whisper_full failed (%d samples)", n);
    }

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT); // read-only; don't copy back
    return env->NewStringUTF(result.c_str());
}

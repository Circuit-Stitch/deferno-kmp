package com.circuitstitch.deferno.core.speech

import android.content.Context
import android.content.pm.ApplicationInfo
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.SharedPreferencesSettings
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android speech bindings (ADR-0018), AppScope process-singletons. The application `Context` is resolved
 * from the AppScope graph (the `PlatformContext` unwrap in core:di), exactly as the secure vault does.
 *
 * The engine is contributed `@IntoSet` into the `Set<SpeechToText>` the [SpeechToTextSelector] ranks: the
 * real on-device whisper.cpp engine ([WhisperSpeechToText] — NDK/CMake/JNI + AudioRecord), resolving its
 * model from the Play Asset Delivery install-time pack ([PlayAssetDeliveryModelLocator], ADR-0019). It
 * reports [UnavailableReason.ModelMissing] until that pack's weights are present on device.
 */
@ContributesTo(AppScope::class)
interface AndroidSpeechBindings {
    @Provides
    @IntoSet
    @SingleIn(AppScope::class)
    fun androidSpeechEngine(context: Context): SpeechToText =
        WhisperSpeechToText(modelLocator = androidWhisperModelLocator(context))

    /**
     * The device-local speech-engine choice ([[App setting]], ADR-0018), SharedPreferences-backed. The
     * file is app-private; it holds only the engine id, never audio or Transcript text.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun speechEnginePreference(context: Context): SpeechEnginePreference =
        SettingsSpeechEnginePreference(
            SharedPreferencesSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )

    /**
     * No shared-layer permission deep-link on Android (#120): the View owns the RECORD_AUDIO prompt and
     * the app-settings intent (`MainActivity.onOpenOsAppSettings`), so the shared seam has nothing to open.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun dictationPermissionSettings(): DictationPermissionSettings = DictationPermissionSettings { null }
}

private const val PREFS_NAME = "deferno_speech"

/**
 * The whisper model locator for Android. **Production** resolves the Play Asset Delivery install-time pack
 * ([PlayAssetDeliveryModelLocator], ADR-0019). A **debuggable** build instead prefers whichever
 * `ggml-*.bin` is sideloaded into the app's external `models/` dir — **newest file wins**, so a plain
 * `installDebug` (which can't carry the asset pack) plus
 *
 * ```
 * adb push ggml-base.en-q5_1.bin /sdcard/Android/data/<applicationId>/files/models/
 * ```
 *
 * makes on-device dictation work, and **swapping model sizes is just another push** (no rebuild) — useful
 * for A/B-ing tiny/base/small on real hardware where small.en is too slow (a 2019 SoC needs a lighter
 * model). Release builds are unchanged — they never look at the sideload dir, only the pack. Dev-only;
 * device/Play-only, so it stays coverage-excluded with the rest of the engine.
 */
internal fun androidWhisperModelLocator(context: Context): WhisperModelLocator {
    val pack = PlayAssetDeliveryModelLocator(context)
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return pack
    val modelsDir = context.getExternalFilesDir("models")
    return WhisperModelLocator {
        modelsDir
            ?.listFiles { f -> f.isFile && f.name.startsWith("ggml-") && f.name.endsWith(".bin") }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
            ?: pack.modelPath()
    }
}

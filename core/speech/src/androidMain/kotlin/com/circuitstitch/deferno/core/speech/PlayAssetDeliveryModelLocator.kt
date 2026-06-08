package com.circuitstitch.deferno.core.speech

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import java.io.File

/**
 * Resolves the whisper model that ships as a Play Asset Delivery **install-time pack** (#92, ADR-0019).
 * For an install-time pack the weights are present from first launch; [com.google.android.play.core.assetpacks.AssetPackManager]
 * reports the pack's on-device location, and the model is read straight from that path — no copy into
 * app storage. The whisper engine reports [SpeechAvailability.Available] only once this resolves.
 *
 * Returns `null` (→ [UnavailableReason.ModelMissing]) when the pack location isn't resolvable yet — which
 * is cheap and side-effect-free, so it is safe to call from `availability()` on the UI path. (A delivery
 * mode that exposes the assets only through the merged `AssetManager` rather than a filesystem path would
 * need a one-time extraction; that fallback is a documented follow-up — install-time delivery on Play and
 * the bundletool `--local-testing` path both expose a real path here.) Coverage-excluded (Play/device-only).
 */
internal class PlayAssetDeliveryModelLocator(
    private val context: Context,
    private val packName: String = PACK_NAME,
    private val assetRelativePath: String = MODEL_ASSET_PATH,
) : WhisperModelLocator {
    override fun modelPath(): String? = runCatching {
        val location = AssetPackManagerFactory.getInstance(context).getPackLocation(packName)
        location?.assetsPath()
            ?.let { File(it, assetRelativePath) }
            ?.takeIf { it.exists() }
            ?.absolutePath
    }.getOrNull()

    private companion object {
        const val PACK_NAME = "speech_model"
        const val MODEL_ASSET_PATH = "models/ggml-small.en-q5_1.bin"
    }
}

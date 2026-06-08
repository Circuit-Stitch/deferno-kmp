package com.circuitstitch.deferno.core.speech

import java.io.File

/**
 * Resolves the on-device path to the whisper model weights on desktop (#94, ADR-0019). The model is
 * **bundled in the installer** (not Play-hosted / not downloaded at runtime): the `small.en` q5 weights
 * ride the Compose Desktop native distribution as an *extra resource*, so they are present from first
 * launch and live next to the packaged app. This seam hides *where exactly* (the packaged resources dir
 * vs a dev source tree) from the engine, mirroring the Android [PlayAssetDeliveryModelLocator] seam.
 *
 * Returns `null` when the model is not resolvable yet, which is why the whisper engine reports
 * [SpeechAvailability.Unavailable] with [UnavailableReason.ModelMissing] rather than crashing.
 * Coverage-excluded (desktop-only, exercised manually; ADR-0006).
 */
fun interface WhisperModelLocator {
    /** Absolute filesystem path to the model file, or `null` if it is not present/resolvable yet. */
    fun modelPath(): String?
}

/**
 * The production desktop [WhisperModelLocator] (#94, ADR-0019): finds the `small.en` weights shipped in
 * the Compose Desktop distribution. In a **packaged app** (and `runDistributable`) Compose sets the
 * `compose.application.resources.dir` system property to the flattened extra-resources directory, so the
 * model resolves at `<resources.dir>/models/<file>`. In **dev** (`./gradlew :app:desktopApp:run`) that
 * property is unset, so it falls back to the source `desktopResources/common` tree the packaging task
 * populates (the working directory is the desktop module). When nothing resolves it returns `null` and
 * the engine reports [UnavailableReason.ModelMissing] (e.g. unit tests / a build with no model fetched).
 *
 * Reads only a JVM system property + a few `File.isFile` checks — no Compose dependency — so it lives in
 * `core:speech` cleanly. Coverage-excluded (desktop-only path; ADR-0006).
 */
internal class BundledModelLocator(
    private val modelRelativePath: String = "models/$MODEL_FILE_NAME",
) : WhisperModelLocator {
    override fun modelPath(): String? {
        val roots = buildList {
            // Packaged app + `runDistributable`: the merged (flattened) extra-resources dir. The tree is
            // flat at runtime — resolve "models/<file>", never "common/models/<file>".
            System.getProperty(RESOURCES_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            // Dev `run`: the working dir is the desktop module, so the source `common` tree is where the
            // `downloadWhisperModel` Gradle task stages the weights. Two spellings cover module-CWD vs repo-CWD.
            add(File("desktopResources/common"))
            add(File("app/desktopApp/desktopResources/common"))
        }
        return roots
            .map { it.resolve(modelRelativePath) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }

    private companion object {
        /** The Compose Desktop system property pointing at the packaged extra-resources dir (ADR-0019). */
        const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"

        /** The bundled `small.en` q5 weights — the same model file Android delivers (ADR-0019). */
        const val MODEL_FILE_NAME = "ggml-small.en-q5_1.bin"
    }
}

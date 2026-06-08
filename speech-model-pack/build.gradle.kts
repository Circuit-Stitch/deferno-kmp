plugins {
    alias(libs.plugins.android.asset.pack)
}

// The whisper model shipped via Play Asset Delivery as an INSTALL-TIME pack (#92, ADR-0019): present
// from first launch, but OFF the base-APK size budget and Play-hosted (not bundled in the binary). The
// app declares this pack in `android { assetPacks }`.
assetPack {
    packName.set("speech_model")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// The model weights (~190 MB) are NOT committed — they are gitignored and fetched on demand so a clean
// checkout stays small and git-clean (ADR-0019: "not self-hosted, not bundled in the binary"). This
// task downloads the pinned small.en q5_1 weights from Hugging Face into the pack's assets if absent.
// It is best-effort: an offline/CI build without the model still produces an app — the whisper engine
// just reports ModelMissing until the pack is populated, never a hard build failure.
val modelFileName = "ggml-small.en-q5_1.bin"

val downloadWhisperModel = tasks.register("downloadWhisperModel") {
    group = "speech"
    description = "Fetches the small.en q5_1 whisper model into the install-time asset pack (ADR-0019)."
    // Capture plain File + String locals (not script-object references) so the task stays
    // configuration-cache compatible.
    val target = layout.projectDirectory.file("src/main/assets/models/$modelFileName").asFile
    val url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelFileName"
    outputs.file(target)
    // Treat an already-present, plausibly-complete file as up to date (avoid re-downloading 190 MB).
    onlyIf { !(target.exists() && target.length() > 150_000_000L) }
    doLast {
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading whisper model from $url …")
        runCatching {
            java.net.URI(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { e ->
            // Don't fail the build offline; remove any partial file so a later build retries.
            target.delete()
            logger.warn(
                "Could not download the whisper model ($url): ${e.message}. The app will build, " +
                    "but on-device dictation reports ModelMissing until the model is present.",
            )
        }.onSuccess {
            logger.lifecycle("Downloaded ${target.length()} bytes to $target")
        }
    }
}

// Surface the task for any asset-packaging in this module too (the app's preBuild is the primary hook).
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadWhisperModel) }

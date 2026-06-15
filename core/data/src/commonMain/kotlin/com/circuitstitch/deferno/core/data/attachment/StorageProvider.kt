package com.circuitstitch.deferno.core.data.attachment

import kotlin.jvm.JvmInline

/**
 * A stable identifier for where a *user/task* attachment's bytes are stored (#210) — the value of the
 * device-local **storage-provider** [[App setting]] ([StorageProviderPreference]). Open (a value class, not
 * an enum) so user-owned cloud providers register as more ids with no type to break (mirrors `SpeechEngineId`
 * / `InferenceEngineId`). The selectable provider governs *user/task* attachments only; **feedback**
 * attachments are always [DefernoBackend] (a fixed provider, so the maintainer can see what users submit).
 */
@JvmInline
value class StorageProviderId(val value: String) {
    companion object {
        /** The **default**: bytes stay on this device (offline-first, ADR-0001), off Deferno's servers. */
        val OnDevice: StorageProviderId = StorageProviderId("on-device")

        /** Deferno's backend object store — the existing presign -> PUT -> commit path; one provider, never required. */
        val DefernoBackend: StorageProviderId = StorageProviderId("deferno-backend")

        /** User-owned Dropbox — a later provider (OAuth out of scope, #210). */
        val Dropbox: StorageProviderId = StorageProviderId("dropbox")

        /** User-owned Google Drive — a later provider (OAuth out of scope, #210). */
        val GoogleDrive: StorageProviderId = StorageProviderId("google-drive")
    }
}

/**
 * Whether a [StorageProviderOption] can be selected right now (#210). On-device + the Deferno backend are
 * [Available]; the user-owned cloud providers are [ComingLater] — shown disabled until their OAuth lands.
 */
sealed interface StorageProviderAvailability {
    /** Selectable now. */
    data object Available : StorageProviderAvailability

    /** A user-owned cloud provider whose integration hasn't landed yet — shown **disabled** (#210). */
    data object ComingLater : StorageProviderAvailability
}

/** One selectable storage provider the Settings row offers (#210): an [id] and its current [availability]. */
data class StorageProviderOption(
    val id: StorageProviderId,
    val availability: StorageProviderAvailability,
)

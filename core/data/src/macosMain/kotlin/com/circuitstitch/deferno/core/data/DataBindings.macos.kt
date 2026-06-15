package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.FileAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.InMemoryAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.InMemoryStorageProviderPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.MacBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PathMonitorConnectivity
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import platform.Foundation.NSHost
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * macOS AppScope actuals (ADR-0014 / ADR-0029), the twin of [IosDataBindings]. The roster
 * ([FileAccountRegistry]) and connectivity ([PathMonitorConnectivity]) are the same cross-Apple
 * `appleMain` implementations iOS uses; the two that genuinely differ are bound here:
 *  - [DeviceName] is the real host name (`NSHost.currentHost.localizedName`) — macOS has no `UIDevice`;
 *  - [BrowserAuthenticator] is [MacBrowserAuthenticator] (#189), the desktop loopback flow (the twin of
 *    the JVM `LoopbackBrowserAuthenticator`): it opens the user's real default browser and captures the
 *    redirect on a `127.0.0.1` listener — NOT a custom scheme (which LaunchServices second-instances on
 *    macOS) and NOT iOS's `ASWebAuthenticationSession`. See the ADR-0026 macOS amendment.
 *
 * macOS per-Account isolation rides the per-Account encrypted DB file + Keychain key (the shared
 * `appleMain` SQLDelight driver), like iOS — no separate sidecar wipe.
 */
@ContributesTo(AppScope::class)
interface MacosDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = FileAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = MacBrowserAuthenticator()

    /** The Mac's user-visible host name (e.g. "Kyle's MacBook Pro"); a static label if unavailable. */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName =
        DeviceName(NSHost.currentHost().localizedName?.takeIf { it.isNotBlank() } ?: "Deferno macOS")

    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = PathMonitorConnectivity()

    /**
     * Storage-provider [[App setting]] + on-device byte store (#210). macOS placeholders for now (the twin
     * of [IosDataBindings]) — the real NSUserDefaults preference + NSFileManager byte store are an Apple
     * follow-up; these keep the graph complete and the macOS klib compiling.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(): StorageProviderPreference = InMemoryStorageProviderPreference()

    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(): AttachmentBytesStore = InMemoryAttachmentBytesStore()

    /** "Keep brain-dump recordings" [[App setting]] (#211). macOS doesn't capture brain dumps — in-memory placeholder. */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(): KeepBrainDumpRecordingsPreference =
        InMemoryKeepBrainDumpRecordingsPreference()
}

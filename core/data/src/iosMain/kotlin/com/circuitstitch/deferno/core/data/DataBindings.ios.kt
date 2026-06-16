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
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.IosBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PathMonitorConnectivity
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import platform.UIKit.UIDevice
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS AppScope actuals (ADR-0014): the persistent file-backed roster (backup-excluded, the
 * SharedPreferences twin — sign-in survives relaunch) and the no-op data store. iOS per-Account
 * isolation is enforced by the per-Account encrypted DB file + Keychain key (the
 * [com.circuitstitch.deferno.core.database] iOS driver), not a separate sidecar wipe.
 */
@ContributesTo(AppScope::class)
interface IosDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = FileAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    /**
     * The browser OAuth leg (ADR-0026): an in-app `ASWebAuthenticationSession` sheet that captures
     * its own redirect — the [AuthRedirectInbox] (`DefernoRoot.forwardAuthRedirect`, #137) is now
     * only the fallback for externally-opened redirects.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = IosBrowserAuthenticator()

    /** Tags a minted token to this device (ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName = DeviceName("Deferno iOS — ${UIDevice.currentDevice.name}")

    /**
     * The connectivity seam (#71/#158, ADR-0016): the `NWPathMonitor` mirror, so the create gate
     * answers before the POST and the outbox driver flushes on the reconnect edge. AppScope —
     * connectivity is a process concern, not per-Account.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = PathMonitorConnectivity()

    /**
     * Storage-provider [[App setting]] + on-device byte store (#210). iOS placeholders for now — the real
     * NSUserDefaults preference + NSFileManager byte store are an iOS follow-up (Android-first); these keep
     * the graph complete and the iOS klib compiling. The selectable provider's iOS surface is SwiftUI.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(): StorageProviderPreference = InMemoryStorageProviderPreference()

    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(): AttachmentBytesStore = InMemoryAttachmentBytesStore()

    /** "Keep brain-dump recordings" [[App setting]] (#211). iOS doesn't capture brain dumps — in-memory placeholder. */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(): KeepBrainDumpRecordingsPreference =
        InMemoryKeepBrainDumpRecordingsPreference()

    /**
     * Item-tree fold-override store (ADR-0034, #227, [[App setting]]). In-memory placeholder — the native
     * iOS SwiftUI tree (and its NSUserDefaults-backed fold store) is a deferred fast-follow; this keeps the
     * graph complete and the iOS klib compiling.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun itemFoldStore(): ItemFoldStore = InMemoryItemFoldStore()
}

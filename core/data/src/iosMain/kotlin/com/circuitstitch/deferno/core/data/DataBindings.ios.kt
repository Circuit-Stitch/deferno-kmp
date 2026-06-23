package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.FileAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.attachment.AppleFileAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.InMemoryStorageProviderPreference
import com.circuitstitch.deferno.core.data.braindump.BrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.BrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.SettingsBrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.SettingsKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.IosBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PathMonitorConnectivity
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.NSUserDefaultsSettings
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
     * The storage-provider [[App setting]] — iOS placeholder still (the selectable provider's iOS surface is a
     * follow-up); the on-device byte store below is now real so a Brain dump's retained recording survives.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(): StorageProviderPreference = InMemoryStorageProviderPreference()

    /**
     * The on-device attachment byte store (#210/#267): real now — `NSFileManager`-backed, so a Brain dump's
     * Salvage recording persists across relaunch and attaches when accepted in the Inbox (was a lossy in-memory
     * placeholder before iOS captured brain dumps).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(): AttachmentBytesStore = AppleFileAttachmentBytesStore()

    /**
     * "Keep brain-dump recordings" [[App setting]] (#211/#267) — `NSUserDefaults`-backed now that iOS captures
     * brain dumps (sharing the device-local `deferno_storage` bag with the salvage counter). A salvage retains
     * the recording regardless; this gates retention for *normal* takes.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(): KeepBrainDumpRecordingsPreference =
        SettingsKeepBrainDumpRecordingsPreference(NSUserDefaultsSettings.Factory().create("deferno_storage"))

    /**
     * The Salvage-draft `Brain dump #n` counter (#265/#267, [[App setting]]) — `NSUserDefaults`-backed so the
     * numbering survives relaunch.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun brainDumpSalvageCounter(): BrainDumpSalvageCounter =
        SettingsBrainDumpSalvageCounter(NSUserDefaultsSettings.Factory().create("deferno_storage"))

    /**
     * The "Brain dump notifications" opt-in (#266, [[App setting]], **default off**). In-memory placeholder
     * until iOS surfaces the toggle — #271 swaps this for an `NSUserDefaults`-backed one (the consent point
     * that also requests notification authorization).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun brainDumpNotificationPreference(): BrainDumpNotificationPreference = InMemoryBrainDumpNotificationPreference()

    /** "Shake to undo" [[App setting]] (ADR-0034 decision 8, #230). iOS has no accelerometer path yet — in-memory placeholder. */
    @Provides
    @SingleIn(AppScope::class)
    fun shakeToUndoPreference(): ShakeToUndoPreference = InMemoryShakeToUndoPreference()

    /**
     * Item-tree fold-override store (ADR-0034, #227, [[App setting]]). In-memory placeholder — the native
     * iOS SwiftUI tree (and its NSUserDefaults-backed fold store) is a deferred fast-follow; this keeps the
     * graph complete and the iOS klib compiling.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun itemFoldStore(): ItemFoldStore = InMemoryItemFoldStore()
}

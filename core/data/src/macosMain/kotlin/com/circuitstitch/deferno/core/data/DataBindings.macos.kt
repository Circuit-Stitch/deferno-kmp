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
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.SettingsBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.SettingsBrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.SettingsKeepBrainDumpRecordingsPreference
import com.russhwolf.settings.NSUserDefaultsSettings
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.data.item.InMemoryShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
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
     * The storage-provider [[App setting]] (#210) — still an in-memory placeholder on macOS, exactly as on
     * iOS ([IosDataBindings]): nothing on either Apple surface lets the person *pick* a provider yet, so a
     * persisted choice would have no writer. This keeps the graph complete and the macOS klib compiling.
     * (Its sibling [attachmentBytesStore] moved off in-memory below — the two are no longer symmetric.)
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(): StorageProviderPreference = InMemoryStorageProviderPreference()

    /**
     * The on-device attachment byte store (#210/#267): real now — `NSFileManager`-backed, the SAME shared
     * `appleMain` implementation iOS binds. macOS gained a Task-detail attachments surface (#368 G11), so an
     * attachment's bytes must survive relaunch instead of dying with the process. The app is unsandboxed, so
     * this resolves under `~/Library/Application Support`.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(): AttachmentBytesStore = AppleFileAttachmentBytesStore()

    /**
     * "Keep brain-dump recordings" [[App setting]] (#211/#267) — `NSUserDefaults`-backed, sharing the
     * device-local `deferno_storage` bag with the salvage counter and the notification opt-in. It landed with
     * the Settings → Storage toggle (#368 G5), a tranche *before* macOS could capture anything — a toggle
     * whose value dies with the process is worse than no toggle. The capture pipeline arrived in #368
     * Tranche 5 and reads this same binding as `BrainDumpPipeline.keepRecordings`
     * (`app/macosApp/src/macosMain/.../BrainDumpRecording.kt`); `DefernoRoot` threads it into
     * `DefaultRootComponent` too, so the Settings surface and the pipeline are one source of truth.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(): KeepBrainDumpRecordingsPreference =
        SettingsKeepBrainDumpRecordingsPreference(NSUserDefaultsSettings.Factory().create("deferno_storage"))

    /**
     * The Salvage-draft `Brain dump #n` counter (#265/#267, [[App setting]]) — `NSUserDefaults`-backed so the
     * numbering survives relaunch, keeping the three `deferno_storage` bindings symmetric with iOS. Live on
     * macOS since #368 Tranche 5 — `processBrainDumpTake` passes it as the pipeline's `salvageCounter`, so a
     * take that yields no transcript is numbered from the same sequence across relaunches.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun brainDumpSalvageCounter(): BrainDumpSalvageCounter =
        SettingsBrainDumpSalvageCounter(NSUserDefaultsSettings.Factory().create("deferno_storage"))

    /**
     * The "Brain dump notifications" opt-in (#266/#271, [[App setting]], **default off**) — `NSUserDefaults`-backed
     * now that macOS surfaces the toggle in Settings → Storage (#368 G5). Since #368 Tranche 5 it also gates
     * the real thing: the pipeline's `BrainDumpNotifier` posts the completion banner only when this reads
     * enabled, so the toggle and the notifier can never disagree.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun brainDumpNotificationPreference(): BrainDumpNotificationPreference =
        SettingsBrainDumpNotificationPreference(NSUserDefaultsSettings.Factory().create("deferno_storage"))

    /** "Shake to undo" [[App setting]] (ADR-0034 decision 8, #230). macOS has no accelerometer path — in-memory placeholder. */
    @Provides
    @SingleIn(AppScope::class)
    fun shakeToUndoPreference(): ShakeToUndoPreference = InMemoryShakeToUndoPreference()

    /**
     * Item-tree fold-override store (ADR-0034, #227, [[App setting]]). In-memory placeholder (the twin of
     * [IosDataBindings]) — the native macOS SwiftUI tree + its NSUserDefaults fold store are an Apple
     * follow-up; this keeps the graph complete and the macOS klib compiling.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun itemFoldStore(): ItemFoldStore = InMemoryItemFoldStore()
}

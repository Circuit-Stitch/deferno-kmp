package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.FileAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.SettingsStorageProviderPreference
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.SettingsKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.SettingsItemFoldStore
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.auth.LoopbackBrowserAuthenticator
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.PollingConnectivity
import com.circuitstitch.deferno.core.data.connectivity.anyNetworkInterfaceUp
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.PreferencesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.util.prefs.Preferences

/**
 * Desktop (JVM) AppScope actuals (ADR-0014): an in-memory roster (a persistent desktop registry is a
 * follow-up) and the no-op data store (the JVM driver's plain DB file has no separate sidecar
 * lifecycle to wipe yet — ADR-0009 desktop posture).
 */
@ContributesTo(AppScope::class)
interface JvmDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(): AccountRegistry = InMemoryAccountRegistry()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(): AccountDataStore = NoOpAccountDataStore

    /** The system-browser OAuth leg (ADR-0026): a loopback listener captures the redirect on desktop. */
    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(): BrowserAuthenticator = LoopbackBrowserAuthenticator()

    /** Tags a minted token to this device (ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName = DeviceName("Deferno Desktop — ${System.getProperty("os.name") ?: "Desktop"}")

    /**
     * The connectivity seam (#71/#158, ADR-0016): the JVM has no push-style reachability callback, so
     * the best-effort interface poll mirrors offline/online for the create gate + the outbox driver's
     * reconnect edge. The poll only runs while observed (an active session), so the process-lifetime
     * scope created here hosts no busy work outside one. AppScope — a process concern, not per-Account.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(): Connectivity = PollingConnectivity(
        probe = { anyNetworkInterfaceUp() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /**
     * The device-local storage-provider choice (#210, [[App setting]]), `java.util.prefs`-backed (the
     * cross-desktop store the speech engine choice uses). Holds only the provider id, never bytes.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(): StorageProviderPreference =
        SettingsStorageProviderPreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/storage")),
        )

    /**
     * The on-device attachment byte store (#210): attachment bytes live as files under
     * `<databasesDir>/attachments` (the same app dir the desktop SQLite files live in), so they survive
     * offline and never leave the device. [databasesDir] is the AppScope binding the DB driver also reads.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(databasesDir: String): AttachmentBytesStore =
        FileAttachmentBytesStore(File(databasesDir, "attachments"))

    /**
     * The device-local "keep brain-dump recordings" choice (#211, [[App setting]]), `java.util.prefs`-backed
     * like the storage-provider choice. Desktop doesn't capture brain dumps yet, so the toggle is inert here
     * (no worker reads it); the binding keeps the graph complete.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(): KeepBrainDumpRecordingsPreference =
        SettingsKeepBrainDumpRecordingsPreference(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/storage")),
        )

    /**
     * The device-local Item-tree fold-override store (ADR-0034, #227, [[App setting]]) — explicit
     * expand/collapse choices keyed by item id, shared by the Tasks tree + the detail subtask outline.
     * `java.util.prefs`-backed like the storage-provider choice; persists across desktop restarts.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun itemFoldStore(): ItemFoldStore =
        SettingsItemFoldStore(
            PreferencesSettings(Preferences.userRoot().node("com/circuitstitch/deferno/storage")),
        )
}

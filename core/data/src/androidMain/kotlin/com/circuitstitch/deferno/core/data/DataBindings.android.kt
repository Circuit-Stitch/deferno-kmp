package com.circuitstitch.deferno.core.data

import android.content.Context
import android.os.Build
import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.attachment.AttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.FileAttachmentBytesStore
import com.circuitstitch.deferno.core.data.attachment.SettingsStorageProviderPreference
import com.circuitstitch.deferno.core.data.attachment.StorageProviderPreference
import com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.SettingsKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.item.ItemFoldStore
import com.circuitstitch.deferno.core.data.item.SettingsItemFoldStore
import com.circuitstitch.deferno.core.data.item.SettingsShakeToUndoPreference
import com.circuitstitch.deferno.core.data.item.ShakeToUndoPreference
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.AndroidAccountDataStore
import com.circuitstitch.deferno.core.data.account.SharedPreferencesAccountRegistry
import com.circuitstitch.deferno.core.data.auth.AndroidBrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.connectivity.NetworkCallbackConnectivity
import com.circuitstitch.deferno.core.database.DatabaseKeyStore
import com.circuitstitch.deferno.core.scopes.AppScope
import com.russhwolf.settings.SharedPreferencesSettings
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

/**
 * Android AppScope actuals (ADR-0002/0009/0014): the persistent SharedPreferences-backed roster and
 * the real per-Account secure-wipe data store (which destroys the encrypted DB + its key on removal).
 * `Context` is resolved from the AppScope graph (the `PlatformContext` unwrap in core:di); the
 * [DatabaseKeyStore] from the core:database Android binding.
 */
@ContributesTo(AppScope::class)
interface AndroidDataBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountRegistry(context: Context): AccountRegistry =
        SharedPreferencesAccountRegistry(context)

    @Provides
    @SingleIn(AppScope::class)
    fun accountDataStore(context: Context, keyStore: DatabaseKeyStore): AccountDataStore =
        AndroidAccountDataStore(context, keyStore)

    /** The system-browser OAuth leg (ADR-0026); MainActivity routes the redirect back via the shared [AuthRedirectInbox] (#137). */
    @Provides
    @SingleIn(AppScope::class)
    fun browserAuthenticator(context: Context, inbox: AuthRedirectInbox): BrowserAuthenticator =
        AndroidBrowserAuthenticator(context, inbox)

    /** Tags a minted token to this device (ADR-0026). */
    @Provides
    @SingleIn(AppScope::class)
    fun deviceName(): DeviceName = DeviceName("Deferno Android — ${Build.MODEL}")

    /**
     * The connectivity seam (#71/#158, ADR-0016): the default-network callback mirror, so the create
     * gate answers before the POST and the outbox driver flushes on the reconnect edge. AppScope —
     * connectivity is a process concern, not per-Account.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun connectivity(context: Context): Connectivity = NetworkCallbackConnectivity(context)

    /**
     * The device-local storage-provider choice (#210, [[App setting]]), SharedPreferences-backed — the
     * app-private file holds only the provider id, never attachment bytes. The twin of the speech/agent
     * engine preferences.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun storageProviderPreference(context: Context): StorageProviderPreference =
        SettingsStorageProviderPreference(
            SharedPreferencesSettings(context.getSharedPreferences(STORAGE_PREFS_NAME, Context.MODE_PRIVATE)),
        )

    /**
     * The on-device attachment byte store (#210): attachment bytes live as app-private files under
     * `filesDir/attachments`, so they survive offline and never leave the device.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun attachmentBytesStore(context: Context): AttachmentBytesStore =
        FileAttachmentBytesStore(File(context.filesDir, "attachments"))

    /**
     * The device-local "keep brain-dump recordings" choice (#211, [[App setting]]) — whether the source
     * recording is retained on accept. SharedPreferences-backed, sharing the device-local app-settings bag
     * with the storage-provider choice (a distinct, namespaced key).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun keepBrainDumpRecordingsPreference(context: Context): KeepBrainDumpRecordingsPreference =
        SettingsKeepBrainDumpRecordingsPreference(
            SharedPreferencesSettings(context.getSharedPreferences(STORAGE_PREFS_NAME, Context.MODE_PRIVATE)),
        )

    /**
     * The device-local "shake to undo" choice (ADR-0034 decision 8, #230, [[App setting]]) — whether a
     * phone shake on the Tasks tree raises the "Undo [operation]?" confirm. SharedPreferences-backed,
     * sharing the device-local app-settings bag with the other App settings (a distinct, namespaced key).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun shakeToUndoPreference(context: Context): ShakeToUndoPreference =
        SettingsShakeToUndoPreference(
            SharedPreferencesSettings(context.getSharedPreferences(STORAGE_PREFS_NAME, Context.MODE_PRIVATE)),
        )

    /**
     * The device-local Item-tree fold-override store (ADR-0034, #227, [[App setting]]) — explicit
     * expand/collapse choices keyed by item id, shared by the Tasks tree + the detail subtask outline.
     * SharedPreferences-backed, sharing the device-local app-settings bag (a distinct, namespaced key).
     */
    @Provides
    @SingleIn(AppScope::class)
    fun itemFoldStore(context: Context): ItemFoldStore =
        SettingsItemFoldStore(
            SharedPreferencesSettings(context.getSharedPreferences(STORAGE_PREFS_NAME, Context.MODE_PRIVATE)),
        )
}

private const val STORAGE_PREFS_NAME = "deferno_storage"

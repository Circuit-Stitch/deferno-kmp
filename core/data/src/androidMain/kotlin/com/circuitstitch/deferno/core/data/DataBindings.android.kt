package com.circuitstitch.deferno.core.data

import android.content.Context
import android.os.Build
import com.circuitstitch.deferno.core.data.account.AccountDataStore
import com.circuitstitch.deferno.core.data.account.AccountRegistry
import com.circuitstitch.deferno.core.data.account.AndroidAccountDataStore
import com.circuitstitch.deferno.core.data.account.SharedPreferencesAccountRegistry
import com.circuitstitch.deferno.core.data.auth.AndroidBrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.AuthRedirectInbox
import com.circuitstitch.deferno.core.data.auth.BrowserAuthenticator
import com.circuitstitch.deferno.core.data.auth.DeviceName
import com.circuitstitch.deferno.core.database.DatabaseKeyStore
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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
}

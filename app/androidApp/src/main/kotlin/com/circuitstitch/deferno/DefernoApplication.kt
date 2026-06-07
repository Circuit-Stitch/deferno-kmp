package com.circuitstitch.deferno

import android.app.Application
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Builds the **process-global [AppComponent]** (ADR-0008 G2 / ADR-0014): the AppScope DI graph holding
 * the cross-Account infrastructure (network client + token provider, AccountManager + secure vault,
 * DatabaseKeyProvider, registry). Presentation is scene-scoped — [MainActivity] builds a per-scene
 * [com.circuitstitch.deferno.shell.RootComponent] over it, and a per-Account
 * [com.circuitstitch.deferno.core.di.AccountComponent] for the Active Account.
 *
 * On startup it hydrates the persisted account roster
 * ([com.circuitstitch.deferno.core.data.account.AccountManager.load]) and seeds the dev-PAT Accounts
 * from BuildConfig (#68, ADR-0012 — debug only; the fields are empty in release).
 */
class DefernoApplication : Application() {

    lateinit var appComponent: AppComponent
        private set

    // Startup work (roster hydration + dev seeding) is off the main thread; the AccountManager's
    // StateFlows then drive the reactive shell. SupervisorJob so one failure doesn't cancel the rest.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appComponent = createAppComponent(
            platform = PlatformContext(this),
            // Debug dev builds talk to staging (the dev-PAT target, ADR-0012). A real environment
            // selector by build type is a follow-up.
            environment = DefernoEnvironment.Staging,
        )
        appScope.launch {
            appComponent.accountManager.load()
            seedDevAccounts()
        }
    }

    /**
     * The dev "sign in" placeholder (#68): (re)seed the dev-PAT Accounts. Wired to the Auth shell's
     * action — a no-op when no PAT is configured or every dev Account is already present.
     */
    fun signInDevAccount() {
        appScope.launch { seedDevAccounts() }
    }

    /** Idempotent: add only the dev Accounts not already in the roster (empty list in release). */
    private suspend fun seedDevAccounts() {
        val manager = appComponent.accountManager
        val existing = manager.accounts.value.map { it.id }.toSet()
        DevAccounts.from(BuildConfig.DEV_ACCOUNTS, BuildConfig.DEV_STAGING_TOKEN)
            .filter { it.account.id !in existing }
            .forEach { devAccount -> manager.addAccount(devAccount.account, devAccount.token) }
    }
}

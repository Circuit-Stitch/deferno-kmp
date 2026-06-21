package com.circuitstitch.deferno

import android.app.Application
import com.circuitstitch.deferno.core.di.AppComponent
import com.circuitstitch.deferno.core.di.createAppComponent
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import software.amazon.app.kmplogger.LogLevel
import software.amazon.app.kmplogger.Logger
import software.amazon.app.kmplogger.logger

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

    // The live Brain dump mic spectrum (per-band 0..1 levels) — the rendezvous between the recorder's
    // audio tap (written from MainActivity's record seam) and the BrainDumpScreen visualizer that reads
    // it. App-scoped on purpose: the recorder lambda is captured once in a retained component while the
    // Compose host re-runs on rotation, so the holder must outlive both to stay connected (ADR-0018 keeps
    // PCM/spectrum off the shared component — this is the Android-only side channel). Starts empty: the
    // writer fills it at @integer/spectrum_bands, and the visualizer renders whatever length it carries.
    val micSpectrum = MutableStateFlow(FloatArray(0))

    // Startup work (roster hydration + dev seeding) is off the main thread; the AccountManager's
    // StateFlows then drive the reactive shell. SupervisorJob so one failure doesn't cancel the rest.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Configure the shared logger ONCE per process, before anything logs (amzn/kmp-logger).
        // The `prefix` makes every tag "Deferno: <ClassName>"; the default strategy writes to
        // Logcat. Release builds emit only WARN + ERROR (a WARN floor); debug builds keep DEBUG.
        Logger.configure(
            minLogLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
            prefix = "Deferno",
        )
        logger.i { "Deferno (Android) starting" }

        appComponent = createAppComponent(
            platform = PlatformContext(this),
            // Environment by build type: debug dev builds talk to staging (the dev-PAT target,
            // ADR-0012); release builds talk to production.
            environment = if (BuildConfig.DEBUG) DefernoEnvironment.Staging else DefernoEnvironment.Production,
        )
        appScope.launch {
            appComponent.accountManager.load()
            seedDevAccounts()
        }
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

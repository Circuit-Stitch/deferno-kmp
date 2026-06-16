package com.circuitstitch.deferno

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.arkivanov.decompose.retainedComponent
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.braindump.recordBrainDumpAudio
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import com.circuitstitch.deferno.shell.RootShell
import java.util.Locale
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Single Compose-host activity. It builds **one [com.circuitstitch.deferno.shell.RootComponent] per
 * scene** (ADR-0008 G2/G3) over the process-global [com.circuitstitch.deferno.core.di.AppComponent]
 * from [DefernoApplication], and renders the navigation shell (Auth ↔ Main → Destination graph,
 * ADR-0013). The Active Account drives the shell; the Main shell is built over a per-Account
 * [com.circuitstitch.deferno.core.di.AccountComponent] (ADR-0014).
 */
class MainActivity : ComponentActivity() {
    // The per-scene navigation root, held so a notification deep-link arriving via onNewIntent can route
    // it (the Brain dump "drafts ready" notification opens the Inbox, ADR-0027/#150 Stage 4).
    private lateinit var root: RootComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ system splash (Theme.Deferno.Starting): the flame in a dark paper-2 circle over a
        // light/dark-aware background. installSplashScreen() applies it (with back-compat) and hands the
        // window to Theme.Deferno at the first frame, which is when the splash dismisses.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val app = application as DefernoApplication
        val appComponent = app.appComponent

        // Retained across configuration changes so shell + Destination navigation survives rotation
        // (Decompose Android integration). The factory runs once per scene; the process-global
        // AppComponent is captured so rotation never rebuilds the data layer (G2). The default
        // coroutine context (Dispatchers.Main) drives the Active-Account collector + navigation.
        // The application Context backs the Settings deep-links (#72) — held, not `this`, so it stays
        // valid across the configuration changes the retained root survives.
        val appContext = applicationContext

        root = retainedComponent { componentContext ->
            val timeZone = TimeZone.currentSystemDefault()
            DefaultRootComponent(
                componentContext = componentContext,
                accountManager = appComponent.accountManager,
                // The Profile Destination's /auth/me identity fetch (#70). AppScope + Active-Account-aware
                // (the bearer plugin attaches the Active Account's PAT per request, ADR-0012).
                authRepository = appComponent.authRepository,
                // Build the per-Account data layer for an Active Account from the DI graph (ADR-0014).
                accountSession = { account ->
                    AccountComponentSession(createAccountComponent(appComponent, account))
                },
                // The paste-PAT sign-in service (#15, ADR-0023) the Auth shell drives.
                signInService = appComponent.signInService,
                today = Clock.System.todayIn(timeZone),
                timeZone = timeZone.id,
                // Settings → App Permissions: deep-link to this app's OS settings screen (#72).
                onOpenOsAppSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", appContext.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                },
                // Settings → Data & Privacy: export/import has no client endpoint at v0.1 (ADR-0015),
                // so it is a REACHABLE web action — open the web app's data surface (AC #3). The origin
                // is derived from the configured environment, so it tracks staging/prod automatically.
                onOpenDataExportImport = {
                    val url = webAppUrl(appComponent.environment, "settings/data")
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                // Settings → Help & Feedback (#375): the in-app feedback form is now a shell overlay
                // (the Settings tap opens it over the foreground Destination), submitting through this
                // AppScope service — the authed client attaches the Active Account's PAT per request.
                feedbackRepository = appComponent.feedbackRepository,
                // Settings → Security & 2FA: open the Active Account's Zitadel console URL in a browser.
                onOpenConsoleUrl = { url ->
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                },
                // Dictation (#92, ADR-0018): the on-device speech engine from the AppScope DI graph, and
                // the device locale it recognizes (a non-English locale reports unavailable rather than
                // mis-transcribing). The New surface's mic drives this.
                speechToText = appComponent.speechToText,
                locale = Locale.getDefault().toLanguageTag(),
                // Speech engine App setting (#93, ADR-0018): the device-local engine catalog from the same
                // AppScope graph — the Settings Destination's "Speech engine" row reads + writes it.
                speechEngineCatalog = appComponent.speechEngineCatalog,
                // Agent inference-engine choice + entitlement gate (#150, ADR-0027): the device-local engine
                // selection + per-Account relay entitlement from the same AppScope graph — the Settings
                // "Agent" row reads + writes it.
                inferenceEngineCatalog = appComponent.inferenceEngineCatalog,
                // Storage-provider App setting (#210): the device-local provider catalog from the same
                // AppScope graph — the Settings Destination's "Storage" row reads + writes it (on-device default).
                storageProviderCatalog = appComponent.storageProviderCatalog,
                // "Keep brain-dump recordings" App setting (#211): the device-local preference from the same
                // AppScope graph — the Settings "Storage" row toggles it, and the brain-dump worker gates
                // recording retention on it.
                keepBrainDumpRecordingsPreference = appComponent.keepBrainDumpRecordingsPreference,
                // "Shake to undo" App setting (#230): the device-local preference from the same AppScope graph
                // — the Tasks tree gates a shake on it, the Settings "Task behavior" row toggles it.
                shakeToUndoPreference = appComponent.shakeToUndoPreference,
                // Brain dump (ADR-0027/#150, Stage 4): the voice_chat overlay records the mic to a WAV and
                // hands it to the background worker on Stop. The shell passes its injected today/timeZone
                // (no Clock.System); the application Context backs the recording + the WorkManager enqueue.
                recordBrainDump = { day, tz -> recordBrainDumpAudio(appContext, day, tz) },
                // The AppScope connectivity monitor (#158): the outbox driver flushes on the
                // offline→online edge and skips passes while known-offline.
                connectivity = appComponent.connectivity,
            )
        }

        // Route system back through the shell (dismiss the active Destination's panes / drill-downs,
        // then fall back to the Plan home); when it has nothing left, fall back to the default (exit).
        val dispatcher = onBackPressedDispatcher
        dispatcher.addCallback(this) {
            if (!root.onBackClicked()) {
                isEnabled = false
                dispatcher.onBackPressed()
            }
        }

        // Fully transparent system bars (transparent scrims) so edge-to-edge app content shows behind
        // them instead of the bright nav-bar contrast scrim the auto default paints in light mode. Icon
        // colours follow the app theme (set in setContent below).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            // Drive the theme from the Active Account's settings so Appearance changes apply LIVE
            // across the whole app (#72): the family selects the palette, and the mode resolves to a
            // dark boolean (Auto follows the OS). Before any account is active the StateFlow seeds the
            // default (Deferno / follow-system), so the Auth shell is themed too (ADR-0002/0014).
            val settings by root.themeSettings.collectAsState()
            val darkTheme = settings.themeMode.resolveDark(isSystemInDarkTheme())
            DefernoTheme(
                palette = when (settings.themeFamily) {
                    ThemeFamily.Deferno -> DefernoPalette.Deferno
                    ThemeFamily.Mono -> DefernoPalette.Mono
                },
                darkTheme = darkTheme,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootShell(root)
                }
            }

            // System-bar icon colours follow the app theme (light theme -> dark icons, dark -> light).
            // The bars themselves are transparent (see enableEdgeToEdge), so the app content shows
            // through behind them rather than a bright nav-bar contrast scrim.
            val lightIcons = !darkTheme
            LaunchedEffect(lightIcons) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightIcons
                    isAppearanceLightNavigationBars = lightIcons
                }
            }
        }

        // A cold start via the OAuth redirect (rare — sign-in starts from a running app) still hands off.
        forwardAuthRedirect(intent)
        // A cold start from tapping the Brain dump "drafts ready" notification opens the Inbox (#150 Stage 4).
        openInboxIfRequested(intent)
    }

    // The OAuth redirect (#15, ADR-0026) re-enters this singleTop activity as a new VIEW intent on the
    // custom scheme; forward it to the inbox the in-flight BrowserAuthenticator is awaiting — the
    // AppScope AuthRedirectInbox resolved from the DI graph, not a static object (#137).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardAuthRedirect(intent)
        openInboxIfRequested(intent)
    }

    private fun forwardAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == AUTH_REDIRECT_SCHEME) {
            (application as DefernoApplication).appComponent.authRedirectInbox.publish(data.toString())
        }
    }

    // The Brain dump worker's "drafts ready" notification carries [EXTRA_OPEN_INBOX] (#150 Stage 4): route
    // it to the Inbox Destination. The root applies it now if a Main shell is up, or once one becomes active.
    private fun openInboxIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_INBOX, false) == true) root.openInbox()
    }

    companion object {
        private const val AUTH_REDIRECT_SCHEME = "com.circuitstitch.deferno"

        /** Intent extra set by the Brain dump notification's PendingIntent to open the Inbox (#150 Stage 4). */
        const val EXTRA_OPEN_INBOX = "com.circuitstitch.deferno.OPEN_INBOX"
    }
}

/**
 * Build a web-app URL for [path] from the configured backend [environment] (#72, AC #3/#4). The web
 * app shares the API host: the env [com.circuitstitch.deferno.core.network.DefernoEnvironment.baseUrl]
 * carries the `/api/` API prefix, so the web origin is that base with the `/api/` suffix dropped — the
 * deep-link tracks staging/prod automatically rather than hard-coding a host. [path] is a relative
 * web-app route (no leading slash), e.g. `settings/data` or `feedback`.
 */
internal fun webAppUrl(environment: DefernoEnvironment, path: String): String {
    val origin = environment.baseUrl.removeSuffix("/").removeSuffix("/api")
    return "$origin/$path"
}

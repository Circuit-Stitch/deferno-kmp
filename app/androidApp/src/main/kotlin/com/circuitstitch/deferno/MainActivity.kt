package com.circuitstitch.deferno

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import com.circuitstitch.deferno.core.speech.AudioSpectrum
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

        // How many spectrum bands the mic tap computes — the visualizer reads the same @integer/spectrum_bands.
        val spectrumBands = resources.getInteger(R.integer.spectrum_bands)

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
                // Settings → Data & Privacy export is now in-app on Android too (#313, ADR-0041): the
                // Settings View builds the Backup zip and saves it via the SAF "Save to…" picker, so the
                // old web-redirect [onOpenDataExportImport] is no longer wired here (desktop/macOS still use it).
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
                recordBrainDump = { day, tz ->
                    recordBrainDumpAudio(
                        appContext,
                        day,
                        tz,
                        // The live mic tap → spectrum bars: compute per-band levels off each PCM chunk and
                        // publish to the app-scoped holder the BrainDumpScreen reads (ADR-0018 side channel).
                        onPcm = { pcm -> app.micSpectrum.value = AudioSpectrum.magnitudes(pcm, spectrumBands) },
                    )
                },
                // The "add a task" App Action's honest confirmation (ADR-0036, #249): the offline-first
                // create is queued + will sync, so the toast says exactly that — never "saved".
                onTaskQueued = { title ->
                    Toast.makeText(appContext, appContext.getString(R.string.task_queued, title), Toast.LENGTH_SHORT).show()
                },
                // The AppScope connectivity monitor (#158): the outbox driver flushes on the
                // offline→online edge and skips passes while known-offline.
                connectivity = appComponent.connectivity,
                // The read-surface session-expiry banner flag (#297): the shared client sets it on a 401.
                reauthRequests = appComponent.reauthRequests,
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
        // A cold start from a Google Assistant App Action deep-link (ADR-0036): open Plan / add a Task.
        routeAppActionsDeepLink(intent)
    }

    // The OAuth redirect (#15, ADR-0026) re-enters this singleTop activity as a new VIEW intent on the
    // custom scheme; forward it to the inbox the in-flight BrowserAuthenticator is awaiting — the
    // AppScope AuthRedirectInbox resolved from the DI graph, not a static object (#137).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardAuthRedirect(intent)
        openInboxIfRequested(intent)
        routeAppActionsDeepLink(intent)
    }

    private fun forwardAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        // Scoped to the auth host so the App Action deep-links below (same scheme, plan/create hosts)
        // don't leak into the OAuth redirect inbox.
        if (data.scheme == APP_SCHEME && data.host == AUTH_REDIRECT_HOST) {
            (application as DefernoApplication).appComponent.authRedirectInbox.publish(data.toString())
        }
    }

    // The Google Assistant App Actions deep-links (ADR-0036, #248/#249), declared in res/xml/shortcuts.xml
    // and matched by the manifest VIEW filter. Routed through `root` (not a session directly) so
    // signed-out opens the Auth shell rather than a blank Plan / a dropped task. The Uri → action parse is
    // the pure, unit-tested [appActionRoute]; this method only dispatches it.
    private fun routeAppActionsDeepLink(intent: Intent?) {
        when (val route = appActionRoute(intent?.data)) {
            AppActionRoute.OpenPlan -> root.openPlan()
            is AppActionRoute.AddTask -> root.addTask(route.title)
            null -> Unit
        }
    }

    // The Brain dump worker's "drafts ready" notification carries [EXTRA_OPEN_INBOX] (#150 Stage 4): route
    // it to the Inbox Destination. The root applies it now if a Main shell is up, or once one becomes active.
    private fun openInboxIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_INBOX, false) == true) root.openInbox()
    }

    companion object {
        /** The OAuth redirect host (#15, ADR-0026): `com.circuitstitch.deferno://auth`. */
        private const val AUTH_REDIRECT_HOST = "auth"

        /** Intent extra set by the Brain dump notification's PendingIntent to open the Inbox (#150 Stage 4). */
        const val EXTRA_OPEN_INBOX = "com.circuitstitch.deferno.OPEN_INBOX"
    }
}

/** The app's custom URI scheme — shared by the OAuth redirect (#15) and the App Action deep-links (#248/#249). */
private const val APP_SCHEME = "com.circuitstitch.deferno"

private const val DEEP_LINK_HOST_PLAN = "plan"
private const val DEEP_LINK_HOST_CREATE = "create"
private const val DEEP_LINK_PARAM_TITLE = "title"

/** A Google Assistant App Action (ADR-0036) resolved from its deep-link [Uri] — the read + capture v1 intents. */
internal sealed interface AppActionRoute {
    /** #248: foreground the Plan Destination ("open my plan"). */
    object OpenPlan : AppActionRoute

    /** #249: create a verbatim, one-off Task ("add <title> to Deferno"). */
    data class AddTask(val title: String) : AppActionRoute
}

/**
 * Map an incoming deep-link [data] to its App Action (ADR-0036, #248/#249), or `null` when it is not one
 * (wrong scheme/host, or a create carrying no title slot). Pure — so the routing parse is unit-testable
 * without launching the activity; [MainActivity.routeAppActionsDeepLink] dispatches the result to the
 * RootComponent (which handles the signed-out / Active-Account targeting).
 */
internal fun appActionRoute(data: Uri?): AppActionRoute? {
    if (data == null || data.scheme != APP_SCHEME) return null
    return when (data.host) {
        DEEP_LINK_HOST_PLAN -> AppActionRoute.OpenPlan
        DEEP_LINK_HOST_CREATE -> data.getQueryParameter(DEEP_LINK_PARAM_TITLE)?.let { AppActionRoute.AddTask(it) }
        else -> null
    }
}

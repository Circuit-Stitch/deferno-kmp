package com.circuitstitch.deferno

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.arkivanov.decompose.retainedComponent
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
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
    override fun onCreate(savedInstanceState: Bundle?) {
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

        val root = retainedComponent { componentContext ->
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
                onSignIn = app::signInDevAccount,
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
                // Settings → Help & Feedback: likewise reachable, not dead text — open the web app's
                // feedback surface (AC #4).
                onOpenSubmitFeedback = {
                    val url = webAppUrl(appComponent.environment, "feedback")
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
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

        enableEdgeToEdge()
        setContent {
            // Drive the theme from the Active Account's settings so Appearance changes apply LIVE
            // across the whole app (#72): the family selects the palette, and the mode resolves to a
            // dark boolean (Auto follows the OS). Before any account is active the StateFlow seeds the
            // default (Deferno / follow-system), so the Auth shell is themed too (ADR-0002/0014).
            val settings by root.themeSettings.collectAsState()
            DefernoTheme(
                palette = when (settings.themeFamily) {
                    ThemeFamily.Deferno -> DefernoPalette.Deferno
                    ThemeFamily.Mono -> DefernoPalette.Mono
                },
                darkTheme = settings.themeMode.resolveDark(isSystemInDarkTheme()),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootShell(root)
                }
            }
        }
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

package com.circuitstitch.deferno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.retainedComponent
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.di.createAccountComponent
import com.circuitstitch.deferno.shell.AccountComponentSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootShell
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
            DefernoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootShell(root)
                }
            }
        }
    }
}

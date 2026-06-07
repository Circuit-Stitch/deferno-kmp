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
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import com.circuitstitch.deferno.shell.RootShell
import com.circuitstitch.deferno.shell.StubAuthGate
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Single Compose-host activity. It builds **one [RootComponent] per scene** (ADR-0008 G2/G3) over the
 * process-global stub repositories from [DefernoApplication], and renders the navigation shell
 * (Auth ↔ Main → Destination graph, ADR-0013) — replacing the throwaway demo host (#27).
 *
 * TODO(DI): source the RootComponent and its dependencies from the DI scene graph (ADR-0008).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as DefernoApplication
        val taskRepository = app.taskRepository
        val planRepository = app.planRepository

        // Retained across configuration changes so shell + Destination navigation survives rotation
        // (Decompose Android integration). The factory runs once per scene; the process-global repos
        // are captured from the Application so rotation never rebuilds the data layer (G2).
        val root = retainedComponent { componentContext ->
            val timeZone = TimeZone.currentSystemDefault()
            DefaultRootComponent(
                componentContext = componentContext,
                authGate = StubAuthGate(),
                taskRepository = taskRepository,
                planRepository = planRepository,
                today = Clock.System.todayIn(timeZone),
                timeZone = timeZone.id,
                output = { output ->
                    when (output) {
                        // Stub mirror of a Tasks "add to plan" into the in-memory plan (the real Plan
                        // write is a domain command that arrives with DI / the API). Uses the concrete
                        // stub repos' snapshot/add — replaced when DI provides real repositories.
                        is RootComponent.Output.AddToPlanRequested ->
                            planRepository.add(taskRepository.snapshot(output.id))
                    }
                },
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

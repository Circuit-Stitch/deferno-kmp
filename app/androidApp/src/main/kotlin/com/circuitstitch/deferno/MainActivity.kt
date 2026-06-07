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
import com.circuitstitch.deferno.demo.DemoApp
import com.circuitstitch.deferno.demo.DemoComponent
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme

/**
 * Single Compose-host activity. For now it hosts the TEMPORARY demo (#27): the shared Tasks + Plan
 * Views over in-memory sample data, so the UI can be seen before auth/DI land.
 *
 * TODO(auth): replace the demo root with the DI-provided scene component graph (ADR-0008).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Retained across configuration changes so navigation/slot state survives rotation (Decompose
        // Android integration). The demo component owns the in-memory repositories + sample data.
        val root = retainedComponent { componentContext -> DemoComponent(componentContext) }

        // Route system back through the demo's single-pane logic (dismiss the active slot / go to the
        // Plan home); when it has nothing left to dismiss, fall back to the default (exit) behavior.
        val dispatcher = onBackPressedDispatcher
        dispatcher.addCallback(this) {
            if (!root.handleBack()) {
                isEnabled = false
                dispatcher.onBackPressed()
            }
        }

        enableEdgeToEdge()
        setContent {
            DefernoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoApp(root)
                }
            }
        }
    }
}

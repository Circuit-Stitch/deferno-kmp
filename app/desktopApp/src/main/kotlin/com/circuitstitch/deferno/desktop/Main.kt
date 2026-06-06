package com.circuitstitch.deferno.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Desktop (JVM) Compose entry point (issue #12).
 *
 * Launches a stub window. The real window — hosting the shared Decompose navigation tree
 * (ADR-0003: desktop View = Compose Desktop) — lands alongside the feature UIs. Runs on
 * any desktop host via `./gradlew :app:desktopApp:run`.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Deferno") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Deferno desktop",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}

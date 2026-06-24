package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.ic_source_github
import com.circuitstitch.deferno.core.designsystem.resources.ic_source_google
import com.circuitstitch.deferno.core.model.ItemSource
import org.jetbrains.compose.resources.painterResource

/**
 * Desktop/JVM resolves the source mark from the design-system Compose resources (the shared-icon home,
 * exposed via its public [Res]) — the same path the desktop shell uses for `ic_voice_chat` etc. The
 * Android twin instead uses native `R.drawable` (Robolectric doesn't serve a dependency's composeResources).
 */
@Composable
internal actual fun sourceMarkPainter(source: ItemSource): Painter = when (source) {
    ItemSource.GitHub -> painterResource(Res.drawable.ic_source_github)
    ItemSource.GoogleCalendar -> painterResource(Res.drawable.ic_source_google)
}

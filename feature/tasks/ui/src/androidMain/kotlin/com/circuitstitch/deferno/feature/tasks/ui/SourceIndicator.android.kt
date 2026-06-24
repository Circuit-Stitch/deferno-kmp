package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.circuitstitch.deferno.core.model.ItemSource

/**
 * Android resolves the source mark from this module's own vector drawables (`R.drawable`). It must be a
 * native Android resource rather than the design-system Compose resource the desktop twin uses: a
 * dependency module's `composeResources` aren't served to the Robolectric screenshot harness (the tree
 * row's marks are screenshot-tested), whereas merged library `res/` is.
 */
@Composable
internal actual fun sourceMarkPainter(source: ItemSource): Painter = when (source) {
    ItemSource.GitHub -> painterResource(R.drawable.ic_source_github)
    ItemSource.GoogleCalendar -> painterResource(R.drawable.ic_source_google)
}

package com.circuitstitch.deferno.shell.ui

import androidx.compose.ui.Modifier

/** Desktop has no OS navigation gestures to exclude, so this is a no-op. */
actual fun Modifier.systemGestureExclusionCompat(): Modifier = this

/** Desktop has no OS back gesture to conflict with, so the left-edge swipe-to-open stays. */
actual val shellEdgeSwipeToOpenEnabled: Boolean = true

package com.circuitstitch.deferno.shell.ui

import androidx.compose.ui.Modifier

/** Desktop has no OS navigation gestures to exclude, so this is a no-op. */
actual fun Modifier.systemGestureExclusionCompat(): Modifier = this

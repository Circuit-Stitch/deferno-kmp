package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

/**
 * Android: mark these bounds as a system-gesture exclusion rect so an edge swipe here opens the drawer
 * instead of being consumed by the OS back gesture (gesture-nav). The OS clamps the total excluded
 * height per edge, so this is best-effort. Applied only to the closed-state edge handle, so when the
 * drawer is open the back gesture is left untouched.
 */
actual fun Modifier.systemGestureExclusionCompat(): Modifier = this.systemGestureExclusion()

package com.circuitstitch.deferno.shell.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

/**
 * Android: mark these bounds as a system-gesture exclusion rect so the leading top-bar control (☰ / ←)
 * isn't consumed by the OS back gesture (gesture-nav) at the left edge. The OS clamps the total excluded
 * height per edge (~200dp), so this is best-effort — but a single small per-control rect stays well under
 * the cap and is honoured, unlike a full-height swipe strip (which clamps to the bottom and leaves the
 * top controls exposed — the screen-wake tap-eating bug).
 */
actual fun Modifier.systemGestureExclusionCompat(): Modifier = this.systemGestureExclusion()

/**
 * Android drops the left-edge swipe-to-open: it conflicts with OS gesture-nav back. Because of the
 * ~200dp edge-exclusion cap a full-height swipe-zone exclusion clamps to the bottom, leaving the top-bar
 * ☰/← controls in the back-gesture zone where their taps are eaten after a screen wake. The drawer opens
 * via the ☰ tap, whose footprint is reserved with [systemGestureExclusionCompat] instead.
 */
actual val shellEdgeSwipeToOpenEnabled: Boolean = false

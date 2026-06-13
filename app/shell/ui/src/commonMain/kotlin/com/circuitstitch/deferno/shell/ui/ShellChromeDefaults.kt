package com.circuitstitch.deferno.shell.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tunable dimensions for [ShellChrome], as Kotlin [Dp] tokens.
 *
 * Deliberately **not** an Android `dimens.xml`: the chrome is shared `commonMain` (Android + desktop)
 * and an Android resource can't feed the desktop (JVM) target — Compose Multiplatform resources have no
 * dimension type. A Kotlin holder is the cross-platform single source of truth, and follows the Compose
 * `*Defaults` convention (`ButtonDefaults`, `DrawerDefaults`, …) — the KMP-idiomatic equivalent of a
 * dimens resource. Public so the values are discoverable and overridable at the call site.
 */
object ShellChromeDefaults {
    /**
     * Width of the start-edge zone that captures a swipe-to-open drag — a touch slop wide enough to
     * grab comfortably even with a phone case over the bezel.
     */
    val EdgeSwipeWidth: Dp = 23.dp

    /** The fully-open radius of the content's leading (top-/bottom-left) corners — it reads as a card. */
    val ContentCornerRadius: Dp = 40.dp

    /** The fully-open elevation of the content, casting the depth shadow off its left edge onto the drawer. */
    val ContentShadowElevation: Dp = 16.dp
}

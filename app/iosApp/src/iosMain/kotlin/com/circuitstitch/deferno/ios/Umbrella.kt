package com.circuitstitch.deferno.ios

/**
 * Swift-facing entry point for the `app:iosApp` umbrella framework (issue #12).
 *
 * This is **public** so it lands in the generated `Deferno.framework` Objective-C header
 * for the SwiftUI app (`iosApp/ContentView.swift`) to call — the SKIE-free baseline that
 * lets the iOS shell render a value from the shared framework. A Kotlin-2.4.0-compatible
 * SKIE release (deferred — see `build.gradle.kts`) will later bridge richer Kotlin types
 * (Flow/suspend/sealed) into idiomatic Swift. Replaced by the exported Decompose root
 * once the feature slices expose it.
 */
class IosGreeting {
    val text: String = "Deferno — iOS scaffold (shared framework)"
}

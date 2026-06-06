// Compose UI convention (ADR-0003: the View is native Compose per platform; the shared
// design system + the per-feature Android Views are Compose). Composed ON TOP of
// `deferno.kmp.library` by modules that hold Composable code — apply both:
//
//   plugins { id("deferno.kmp.library"); id("deferno.compose") }
//
// It applies the two Compose Gradle plugins (the same pair app/desktopApp applies directly):
//
//   org.jetbrains.compose             — Compose Multiplatform runtime + the `compose {}` DSL,
//                                       including Compose Resources (fonts/images in
//                                       commonMain/composeResources, exposed as a `Res` class).
//   org.jetbrains.kotlin.plugin.compose — the Compose *compiler* plugin (versioned with Kotlin,
//                                       which is what keeps Compose Multiplatform Kotlin-2.4.0-safe).
//
// Dependencies (compose.runtime / compose.material3 / compose.components.resources / …) stay in
// each module's build file, in the source set that needs them — commonMain for the shared design
// system, androidMain for the per-feature Android Views (ADR-0004). On the Android target the
// `compose.*` accessors resolve to the matching `androidx.compose.*` artifacts.

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

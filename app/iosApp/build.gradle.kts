plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The iOS entry point: bundles the shared feature slices into a single `Deferno`
// framework that the Xcode project (see ./iosApp, added on macOS) links against.
// Apple-only module — no android/jvm targets. Klibs cross-compile on any host;
// linking the framework binary happens on a macOS runner (ADR-0006).
kotlin {
    jvmToolchain(17)

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Deferno"
            isStatic = true
            // When features expose Swift-facing APIs, switch these to `export(...)`
            // (with `api(...)` below) so SKIE can bridge them into idiomatic Swift.
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":feature:auth"))
            implementation(project(":feature:tasks"))
            implementation(project(":feature:plan"))
            implementation(project(":core:designsystem"))
        }
    }
}

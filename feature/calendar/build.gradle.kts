plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.calendar"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common")) // componentScope() (#174)
            implementation(project(":core:model"))
            implementation(project(":core:domain"))
            implementation(project(":core:data"))
            // `api`, not `implementation`: `CalendarComponent`/`DefaultCalendarComponent` expose Decompose
            // (`ComponentContext`) and coroutines `StateFlow` in their public API, so View consumers
            // (`:feature:calendar:ui`) must see them.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The Compose Views for this slice live in the sibling `:feature:calendar:ui` module (Compose
        // platforms only, no iOS) — see the note in feature/tasks/build.gradle.kts.
    }
}

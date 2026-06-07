plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.auth"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            // `api`, not `implementation`: the component interface exposes Decompose (`ComponentContext`)
            // and coroutines (`StateFlow`) in its public signatures, so the View consumer
            // (`:feature:auth:ui`) must see them. Decompose api-exposes Essenty. Mirrors `:feature:tasks`.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The Compose View for this slice lives in the sibling `:feature:auth:ui` module (a Compose UI
        // library on the Compose platforms only — Android + JVM, no iOS). The Compose *compiler* plugin
        // can't be applied here because this module also targets iOS (SwiftUI, ADR-0004); so the slice's
        // logic (this module, iOS-capable) and its Android-native screen stay in separate modules (#27).
    }
}

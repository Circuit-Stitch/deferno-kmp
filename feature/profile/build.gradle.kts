plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.profile"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:data"))
            // `api`, not `implementation`: the component interface exposes Decompose (`ComponentContext`)
            // and coroutines (`StateFlow`) in its public signatures, so the View consumer
            // (`:feature:profile:ui`) must see them. Decompose api-exposes Essenty. Mirrors `:feature:auth`.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The Compose View for this slice lives in the sibling `:feature:profile:ui` module (Android +
        // JVM, no iOS): the Compose compiler plugin is module-wide and would break this module's iOS
        // compilation, so the slice's logic (here, iOS-capable) and its Android-native screen stay
        // separate (#27, ADR-0004).
    }
}

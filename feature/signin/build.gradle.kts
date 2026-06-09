plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.signin"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            // The SignInService seam (#15, ADR-0023): validate a pasted PAT → establish the Account.
            implementation(project(":core:data"))
            // `api`, not `implementation`: the component interface exposes Decompose (`ComponentContext`)
            // and coroutines (`StateFlow`) in its public signatures, so the View consumer
            // (`:feature:signin:ui`) and the Auth shell must see them. Mirrors `:feature:auth`.
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // The component tests assert synchronously against state.value (a StandardTestDispatcher
            // drives the init/submit coroutines) — no Turbine flow harness needed.
            implementation(libs.kotlinx.coroutines.test)
        }
        // The Compose View for this slice lives in the sibling `:feature:signin:ui` module (Android +
        // JVM/desktop, no iOS). The Compose compiler plugin can't be applied here because this module
        // also targets iOS (SwiftUI, ADR-0004 #27), so the slice's logic (this module, iOS-capable) and
        // its native screen stay in separate modules.
    }
}

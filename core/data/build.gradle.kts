plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.data"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(project(":core:secure"))

            // StateFlow for the observable Active Account + accounts list (ADR-0001, issue #14).
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            // Flow test stack (ADR-0006): runTest + Turbine for the AccountManager emission tests.
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

plugins {
    id("deferno.kmp.library")
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.di"
    }

    sourceSets {
        commonMain.dependencies {
            // Account — the real AccountScope binding the graph resolves per scene (issue #14).
            implementation(project(":core:model"))
        }
    }
}

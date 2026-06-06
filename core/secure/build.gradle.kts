plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.secure"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
        }
    }
}

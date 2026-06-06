plugins {
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.model"
    }

    sourceSets {
        commonMain.dependencies {
            // Domain timestamps (Instant) + Plan dates (LocalDate). The model is the shared
            // contract the network mapper (#18) and repositories (#22) both map onto, so the
            // date/time types are part of its public API (`api`, not `implementation`).
            api(libs.kotlinx.datetime)
        }
    }
}

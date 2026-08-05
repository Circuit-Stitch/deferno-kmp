plugins {
    id("deferno.kmp.library")
    // Embeds contracts/recurrence-corpus/*.json into commonTest so the golden corpus that pins the
    // offline occurrence-grid expander (#401, ADR-0053 decision 5) loads on every KMP target with no
    // runtime file IO — including the two Apple targets, neither of which has a Swift test target.
    id("deferno.contract-fixtures")
}

contractFixtures {
    sourceDir = rootProject.layout.projectDirectory.dir("contracts/recurrence-corpus")
    packageName = "com.circuitstitch.deferno.core.model.corpus"
    objectName = "RecurrenceCorpus"
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
        commonTest.dependencies {
            // Reads the embedded corpus. Test-only, and only through the untyped JsonElement tree, so
            // this module still needs no kotlinx-serialization compiler plugin — the production model
            // stays free of wire concerns (ADR-0011 condense-at-edge).
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

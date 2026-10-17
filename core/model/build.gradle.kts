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
        // The kind × field-combination corpus (`KindShapes.kt`) is compiled into this module's tests and
        // into `core:data`'s, which is what lets the storage-fidelity gate (#422) run over the same
        // shapes the recipe round trip does rather than over a second corpus that can drift. It sits in
        // its own directory rather than in `commonTest` so the sharing is a single named file set, and
        // it is a plain directory rather than a Gradle source set because KMP publishes no test
        // artifact — each consumer compiles the sources into its own test compilation.
        commonTest { kotlin.srcDir("src/testFixtures/kotlin") }

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

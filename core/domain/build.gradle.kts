plugins {
    id("deferno.kmp.library")
    // This module contributes the AccountScope CommandExecutor binding (ADR-0007/0014) via a
    // @ContributesTo module, so it hosts kotlin-inject + anvil.
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.domain"
    }

    sourceSets {
        commonMain.dependencies {
            // The DI scope markers (AccountScope) the @ContributesTo CommandExecutor binding references.
            api(project(":core:scopes"))
            // The command registry (#26, ADR-0007) is pure-data commands + a dispatch executor.
            // core:model supplies TaskId/Task/WorkingState (and kotlinx-datetime's LocalDate, via its
            // `api`) named in the public command signatures; core:data supplies the TaskWriter/PlanWriter
            // write seam the executor dispatches to (offline-first apply + enqueue). Both reach consumers
            // the same way core:data's own public API does, so no extra api/datetime wiring is needed.
            implementation(project(":core:model"))
            implementation(project(":core:data"))
        }

        commonTest.dependencies {
            // CommandExecutor.execute is suspend; the dispatch/enablement tests drive it via runTest
            // against the recording writer fakes (ADR-0006 JVM-fast path).
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

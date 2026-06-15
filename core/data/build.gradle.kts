plugins {
    id("deferno.kmp.library")
    // This module contributes the bulk of the AppScope spine + AccountScope data layer (ADR-0014)
    // via distributed @ContributesTo modules, so it hosts kotlin-inject + anvil.
    id("deferno.di")
}

// The live `/auth/me` tracer (StagingAuthMeIntegrationTest, #20) reads `deferno.staging.baseUrl` /
// `deferno.staging.apiToken` as test system properties. Those are already surfaced from the gitignored
// `local.properties` to every Test task by the `deferno.kmp` convention plugin (config-cache-safe via
// `providers.fileContents`), so no per-module wiring is needed here — the test skips itself when blank.

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
            // The DI scope markers (App/Account scope) the @ContributesTo bindings reference.
            api(project(":core:scopes"))

            // StateFlow for the observable Active Account + accounts list (ADR-0001, issue #14).
            implementation(libs.kotlinx.coroutines.core)

            // The repository layer (#22) wraps the SQLDelight `DefernoDatabase` directly: the
            // SQLDelight runtime supplies the `Transacter`/`Query` types the local store touches,
            // and the coroutines extensions supply `Query.asFlow().mapToList(...)` — the
            // observe-via-Flow-only seam (ADR-0001). core:database hides these as `implementation`,
            // so the repository module declares them itself.
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            // The remote sources (#22) call `HttpClient.requestApi` from core:network, which is
            // again `implementation`-scoped there, so the Ktor client core is declared here too.
            implementation(libs.ktor.client.core)

            // The offline outbox (#23) builds each intent's minimal PATCH/POST body as a `JsonObject`
            // (never emitting an absent field — ADR-0011) and renders it to the stored request string.
            // Only the kotlinx.serialization runtime is needed (no @Serializable classes here, so no
            // compiler plugin); core:network keeps its serialization dep `implementation`-scoped.
            implementation(libs.kotlinx.serialization.json)

            // The device-local storage-provider [[App setting]] (#210): the multiplatform-settings
            // commonMain `Settings` over SharedPreferences (Android) / java.util.prefs (JVM) / NSUserDefaults
            // (Apple), each supplied by a platform binding — the same store the speech/agent engine choices use.
            implementation(libs.multiplatform.settings)
        }

        commonTest.dependencies {
            // Flow test stack (ADR-0006): runTest + Turbine for the AccountManager emission tests
            // and the reconcile/hydration/observe tests (#22).
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)

            // MockEngine + JSON content negotiation drive the KtorTaskRemoteSource tests over the
            // same `requestApi` envelope pipeline that ships, with no real network (#22, ADR-0006).
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        // The real-SQL integration test (#22, ADR-0006 JVM-fast path): build a `DefernoDatabase`
        // over an in-memory JdbcSqliteDriver and prove the SqlDelight local store round-trips
        // through real SQLite. The commonTest fakes prove the reconcile/hydration algorithm.
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

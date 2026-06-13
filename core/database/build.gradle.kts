plugins {
    id("deferno.kmp.library")
    // SQLDelight schema codegen (issue #21). Applied per-module via alias — same pattern as
    // kotlin-serialization in core/network — since only this module hosts the database.
    alias(libs.plugins.sqldelight)
    // This module contributes the AppScope DatabaseKeyStore + AccountScope SqlDriverFactory
    // bindings (ADR-0014) via distributed @ContributesTo modules, so it hosts kotlin-inject + anvil.
    id("deferno.di")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.database"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            // The DI scope markers (App/Account scope) the @ContributesTo bindings reference.
            api(project(":core:scopes"))

            // SQLDelight runtime + the coroutines Flow extensions the repositories observe the
            // DB through (ADR-0001 observe-via-Flow-only). The driver itself is per-target.
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.kotlinx.coroutines.core)
        }

        // Per-target drivers (ADR-0001/0002): Android (encrypted via SQLCipher), iOS (encrypted
        // via SQLiter), JVM (JdbcSqliteDriver — desktop + the in-memory test path). The
        // engine-agnostic queries live in commonMain; these supply only the driver + encryption.
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        // Both Apple targets (iOS + macOS, ADR-0029) ride the SQLDelight native driver + SQLiter
        // encryption from the shared appleMain.
        appleMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        // commonTest builds an in-memory database via the `inMemorySqlDriver` expect/actual.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // The JVM and Android-host (also JVM) actuals open an in-memory JdbcSqliteDriver — the
        // ADR-0006 JVM-fast path, no device. The iOS actual rides native-driver (in iosMain).
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("DefernoDatabase") {
            packageName.set("com.circuitstitch.deferno.core.database.sql")
            // Write a frozen `.db` snapshot of each schema version so migrations are verified
            // against it (ADR-0022). `verifyMigrations` (a `check` dependency, run by CI) fails the
            // build if the committed snapshots + `.sqm` migrations don't reproduce the `.sq` schema.
            // Schema authored in `.sq` (deriveSchemaFromMigrations stays off); each schema change adds
            // a new numbered `<N>.sqm` + `databases/<N>.db` and never edits a released one — see the
            // runbook in ADR-0022.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

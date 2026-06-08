plugins {
    // Shared, Compose-FREE KMP library (Android + JVM + iOS), per ADR-0017: it holds the app Shell
    // *components* (RootComponent, MainShellComponent, Destination, AccountSession, NewComponent,
    // AuthShellComponent) + the DevAccounts parser, so one set of shell components can be rendered
    // three ways — the per-platform Views stay in each app entry point. It deliberately applies NO
    // Compose plugin and declares NO Compose dependency: the Compose compiler is module-wide and
    // would break this module's iOS target (ADR-0004 #27). app/shell sits above feature/* (it
    // composes the slices) and below the per-platform app/* entry points.
    id("deferno.kmp.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.shell"
    }

    sourceSets {
        commonMain.dependencies {
            // The feature slices the Main shell composes into its Destination graph — the *logic*
            // modules only (the Compose Views live in each slice's `:ui` submodule, consumed directly
            // by the app entry points, ADR-0004 #27).
            implementation(project(":feature:tasks"))
            implementation(project(":feature:plan"))
            implementation(project(":feature:calendar"))
            implementation(project(":feature:profile"))
            implementation(project(":feature:settings"))
            // The per-Account DI graph the production AccountSession adapts (ADR-0014); the data
            // repositories the shell observes + the AccountManager the RootComponent keys off; the
            // command executor + commands it drives (add-to-plan, working-state, online-only create);
            // the create-payload DTOs the New surface builds; and the shared model.
            implementation(project(":core:di"))
            implementation(project(":core:data"))
            implementation(project(":core:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:model"))
            // `api`, not `implementation`: the component interfaces expose Decompose types (`Value`,
            // `ChildStack`, `ChildSlot`, `ComponentContext`) and coroutines `StateFlow` in their public
            // signatures, so the per-platform Views (in the app entry points) must see them — mirroring
            // the feature logic modules. Decompose api-exposes Essenty (the Lifecycle the tests drive).
            api(libs.decompose)
            api(libs.kotlinx.coroutines.core)
            // `api` too: AccountSession.addToPlan exposes kotlinx.datetime.LocalDate in its signature.
            api(libs.kotlinx.datetime)
        }
        // The component unit tests run on the JVM-fast path (`:app:shell:jvmTest`). kotlin.test comes
        // from the deferno.kmp convention; the demo/fake repositories live alongside the tests in
        // commonTest (confined to the test source set — never on a main classpath, ADR-0017), and the
        // shell's main deps (core/feature, Decompose, coroutines, datetime) are visible transitively.
    }
}

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
            implementation(project(":core:common")) // componentScope() (#174)
            // The feature slices the Main shell composes into its Destination graph — the *logic*
            // modules only (the Compose Views live in each slice's `:ui` submodule, consumed directly
            // by the app entry points, ADR-0004 #27).
            // The paste-PAT sign-in slice (#15, ADR-0023) the Auth shell hosts (its logic module only;
            // the Compose View lives in :feature:signin:ui, consumed by the app entry points).
            implementation(project(":feature:signin"))
            implementation(project(":feature:tasks"))
            implementation(project(":feature:plan"))
            // The server-mediated Assistant chat slice (ADR-0040, #282): the shell composes its
            // DefaultAssistantComponent into a conditionally-present Destination. Its Compose Views are
            // deferred (iOS SwiftUI ships first), so there is no :feature:assistant:ui peer yet.
            implementation(project(":feature:assistant"))
            // The Inbox Destination's logic (ADR-0015 Inbox amendment) — its Compose View lives in
            // :feature:braindumps:ui, consumed by the app entry points.
            implementation(project(":feature:braindumps"))
            implementation(project(":feature:calendar"))
            implementation(project(":feature:profile"))
            // `api`: ChromeTitle.ForSettingsCategory exposes SettingsCategory in ChromeSpec's public
            // shape, so shell consumers (the ui module, the SwiftUI bridges) see the type transitively.
            api(project(":feature:settings"))
            // The per-Account DI graph the production AccountSession adapts (ADR-0014); the data
            // repositories the shell observes + the AccountManager the RootComponent keys off; the
            // command executor + commands it drives (add-to-plan, working-state, online-only create);
            // the create-payload DTOs the New surface builds; and the shared model.
            implementation(project(":core:di"))
            // On-device speech-to-text (#92, ADR-0018): the SpeechToText seam the New surface's
            // Dictation drives. The production engine is resolved from the DI graph (AppComponent) and
            // threaded in by the host; tests pass a fake or omit it (dictation simply unavailable).
            implementation(project(":core:speech"))
            // The Agent opt-in + entitlement gate (#150, ADR-0027): the AgentGate the shell threads from
            // the AppComponent into the Settings Destination (like the speech catalog). Default AgentGate.Inert
            // in tests.
            implementation(project(":core:agent"))
            // `api`: ActivityFeedRow (ActivitySummary/ActivitySource) and FeedbackStatus.Failed
            // (FeedbackResult.Failed.Reason) expose core:data types in the shell's public state, so
            // consumers (the ui module, the SwiftUI bridges) see them transitively.
            api(project(":core:data"))
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
        commonTest.dependencies {
            // runTest + the virtual scheduler (advanceUntilIdle/backgroundScope) for the New surface's
            // Dictation state-machine tests (#92): the streaming listen() Flow is driven deterministically.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

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
            // Scope markers + PlatformContext (ADR-0008 / ADR-0014) — the low-level DI vocabulary
            // the merged components and every contributor share. `api` because the merged
            // components' public surface (e.g. createAppComponent's PlatformContext) leaks it.
            api(project(":core:scopes"))
            // Account — the real AccountScope binding the graph resolves per scene (issue #14).
            implementation(project(":core:model"))

            // The contributor modules whose @ContributesTo bindings merge into the App/Account
            // components here (ADR-0014). core:di is the merge site: the generated merged graph
            // names each contributed impl (AccountManager, HttpClient, the repositories, …), so
            // every contributor must be on this module's compile classpath. `api` for the
            // surfaces the components re-expose (AccountManager, the repositories); the rest are
            // pulled in transitively but listed explicitly for the merge.
            api(project(":core:data"))
            // `api`: DefernoEnvironment appears in createAppComponent's public signature.
            api(project(":core:network"))
            // `api`: CommandExecutor is re-exposed on AccountComponent's public surface.
            api(project(":core:domain"))
            // `api`: AccountDatabaseFactory is re-exposed on AppComponent for the child AccountScope.
            api(project(":core:database"))
            implementation(project(":core:secure"))
            // Speech is an AppScope device capability (ADR-0018, #92): its SpeechToText seam + selector
            // + per-platform engine multibinding merge here. `api` because AppComponent re-exposes
            // `speechToText: SpeechToText` for the shell's Dictation surface, leaking the seam type.
            api(project(":core:speech"))
            // Agent inference is an AppScope device capability too (ADR-0027, #147): the
            // InferenceEngine seam + the Koog Anthropic-format engine merge here via AgentBindings.
            // `api` because the merged AppComponent implements AgentBindings (a public supertype),
            // so the contributed interface must resolve on every consumer's compile classpath.
            api(project(":core:agent"))
        }

        jvmMain.dependencies {
            // The JVM-only Sidecar substrate (ADR-0024/0025): SidecarBindings provides the process-wide
            // SidecarClient every capability port shares (#119 speech now, #120 permissions next). `api`
            // because SidecarClient appears on the bindings' (merged-component) public surface.
            api(project(":core:sidecar"))
        }
    }
}

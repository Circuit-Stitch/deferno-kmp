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
            implementation(project(":core:database"))
            implementation(project(":core:secure"))
        }
    }
}

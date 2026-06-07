package com.circuitstitch.deferno.gradle

/**
 * Single source of truth for the coverage gate (ADR-0006). It defines what counts as
 * "logic" (vs generated code / boilerplate) and the minimum line-coverage bound, shared
 * by the two coverage conventions so they always measure exactly the same thing:
 *
 * - `deferno.coverage` standardises these exclusions on every shared module's *own* Kover
 *   report (so a local `:core:di:koverHtmlReport` matches the gate).
 * - `deferno.coverage.aggregation` enforces the hard gate over the *merged* shared-core
 *   report. In Kover's `kover(project(...))` model the merged report uses the aggregating
 *   project's filters — not the per-module ones — so the gate needs its own copy of this
 *   list; sharing it here keeps the two from drifting.
 *
 * Dependency *versions* live in `gradle/libs.versions.toml`; SDK levels / the JVM toolchain
 * live in [ProjectConfig]. This is the coverage policy.
 */
object CoverageConfig {
    /**
     * The hard CI gate: minimum % of LINE coverage across the merged shared core. ADR-0006
     * puts it at ~85–90%; 85 is the floor of that band — raise it as the core matures.
     * Deliberately not 100%: we measure logic, not boilerplate, and avoid tests written
     * only for coverage.
     */
    const val GATE_MIN_LINE_PERCENT = 85

    /**
     * Class-name globs excluded from coverage — generated code + compiler boilerplate, so
     * the gate measures hand-written logic rather than plumbing (ADR-0006).
     */
    val EXCLUDED_CLASSES: List<String> = listOf(
        "*.BuildConfig",
        // kotlin-inject + kotlin-inject-anvil generated DI graph (issue #10):
        "*KotlinInject*",    // anvil merged components + kotlin-inject component impls
        "*CreateComponent*", // anvil @CreateComponent (KMP create) factories
        // The distributed @ContributesTo binding modules (issue #68, ADR-0014): each is an interface
        // of @Provides one-liners naming how an impl is constructed (the AppScope spine + the
        // per-Account data layer). Pure DI wiring — "measure logic, not boilerplate" — run only when
        // the real graph is built (the app), not the headless JVM gate. Named `*Bindings` by
        // convention; the merged graph they feed IS compile-validated on every target.
        "*Bindings",
        // The desktop/iOS no-op AccountDataStore (issue #68): a do-nothing binding for platforms with
        // no separate per-Account data-wipe yet. No logic to measure; a test would be hollow.
        "com.circuitstitch.deferno.core.data.account.NoOpAccountDataStore",
        // Kotlin interface default-method JVM bridges — compiler artifacts, never invoked
        // (the real provider on the interface is what runs), so uncoverable boilerplate.
        "*DefaultImpls",
        // DI scope-key marker objects (ADR-0008, in core:scopes): they exist only as annotation
        // arguments (@MergeComponent(AppScope::class), @SingleIn(...)) and are never instantiated,
        // so their synthetic <init> can't be covered without a hollow test. PlatformContext is a
        // per-platform host handle (Context / databases dir) — a data holder, no logic. Not measured.
        "com.circuitstitch.deferno.core.scopes.*Scope",
        "com.circuitstitch.deferno.core.scopes.PlatformContext*",
        // Platform secure-storage actuals (issue #13): real crypto / OS-keychain access that
        // runs only on a device, an Apple target, or a desktop OS — exercised by instrumented
        // & native tests, not the headless JVM gate (ADR-0006). The SecretVault contract and
        // its in-memory fake ARE measured (commonMain, via the round-trip tests). The trailing
        // `*` also excludes each actual's nested/synthetic classes (its `Companion`, a `by lazy`
        // lambda, etc.); it does not match the measured `InMemorySecretVault` / `SecretVault`.
        "com.circuitstitch.deferno.core.secure.AndroidKeystoreSecretVault*",
        "com.circuitstitch.deferno.core.secure.KeychainSecretVault*",
        "com.circuitstitch.deferno.core.secure.DesktopSecretVault*",
        // Platform account-storage actuals (issue #68): the SharedPreferences-backed roster and the
        // Android per-Account data-wipe both touch device storage, exercised by instrumented tests,
        // not the headless gate (same rationale as the secure-storage actuals). The AccountRegistry
        // contract and the roster (de)serialization (AccountRosterCodec, commonTest) ARE measured.
        "com.circuitstitch.deferno.core.data.account.SharedPreferencesAccountRegistry*",
        "com.circuitstitch.deferno.core.data.account.AndroidAccountDataStore*",
        // Compose @Composable glue (ADR-0006: "thin UI glue"; Views are screenshot-tested, not
        // unit-tested on the headless JVM gate). The design-system colour *tokens* ARE measured
        // (designsystem commonTest); the @Composable theme + typography builders that resolve fonts
        // in composition are not. `*ComposableSingletons*` is the Compose-compiler-generated holder
        // for composable lambdas (every Compose file gets one) — never hand-written logic.
        "com.circuitstitch.deferno.core.designsystem.theme.ThemeKt",
        "com.circuitstitch.deferno.core.designsystem.theme.TypeKt",
        "*ComposableSingletons*",
    )

    /** Package globs excluded from coverage (generated DI contribution hints + platform glue). */
    val EXCLUDED_PACKAGES: List<String> = listOf(
        "amazon.lastmile.inject", // anvil contribution-hint classes
        // Per-target Ktor engine providers (issue #17): real OkHttp/Darwin engine creation
        // runs only on a device / desktop / Apple target and is exercised by integration, not
        // the headless MockEngine gate (ADR-0006) — same rationale as the secure-storage
        // actuals above. The engine-agnostic client config + envelope mapping (commonMain) ARE
        // measured, via the MockEngine tests.
        "com.circuitstitch.deferno.core.network.platform",
        // Per-target database driver factories (issue #21): SQLCipher (Android), SQLiter
        // encryption (iOS), and the JdbcSqliteDriver file driver (desktop) all open a real,
        // platform-bound database and run only on a device / Apple target / desktop — exercised
        // by integration, not the headless gate (ADR-0006). The schema, queries, and the
        // in-memory test driver (commonMain/commonTest) ARE measured. The Android DB-key provider
        // (AndroidDatabaseKeyProvider, issue #68) lives here too — device crypto, instrumented-tested.
        "com.circuitstitch.deferno.core.database.driver",
        // Feature Compose Views (#27): the thin state-renderers in each slice's `:feature:*:ui`
        // module (Android screens in androidMain + reusable atoms in commonMain). They hold no
        // business logic — they read the component's StateFlow/slots and call its methods — and are
        // exercised by Compose UI tests + Roborazzi screenshots, not the headless JVM coverage gate
        // (same rationale as the designsystem theme above, ADR-0006). The shared Decompose components
        // driving them ARE measured (feature commonTest, #25/#20).
        "com.circuitstitch.deferno.feature.auth.ui",
        "com.circuitstitch.deferno.feature.tasks.ui",
        "com.circuitstitch.deferno.feature.plan.ui",
    )
}

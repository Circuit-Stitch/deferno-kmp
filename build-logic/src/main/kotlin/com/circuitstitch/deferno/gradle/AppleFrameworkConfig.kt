package com.circuitstitch.deferno.gradle

/**
 * Single source of truth for the two bespoke Apple umbrella-framework modules — `app/iosApp`
 * (iosArm64 + iosSimulatorArm64) and `app/macosApp` (macosArm64, ADR-0029). They link the *same*
 * `Deferno.framework` twice, once per Apple OS, so anything that must not drift between them lives
 * here and is applied by the `deferno.apple.framework` convention.
 *
 * Sibling of [CoverageConfig]: policy the convention plugins read, not a dependency version (those
 * stay in `gradle/libs.versions.toml`).
 */
object AppleFrameworkConfig {

    /**
     * The framework's `CFBundleIdentifier`. Kotlin/Native can't infer one for a framework whose
     * sources span this many packages, and warns on every link. Deliberately DISTINCT from either
     * app's own bundle id — the iOS app's varies per env flavour (ADR-0047) and macOS ships its own
     * (`com.circuitstitch.deferno.macos`), while the framework both of them embed stays put.
     */
    const val BUNDLE_ID = "com.circuitstitch.deferno.Deferno"

    /**
     * Fully-qualified-name prefixes whose Obj-C-export **name collisions** SKIE may silence.
     *
     * A Kotlin `description` member collides with `-[NSObject description]` (and `first`/`last` with
     * Swift's own), so SKIE renames it to `description_`/`first_()` and warns on every link — the
     * compiler-picked spelling being unstable is the point of the warning. Where Swift actually reads
     * the member we name it at the declaration instead (`@ObjCName`, currently the four item kinds'
     * `description` → `itemDescription`). What's left is code Swift never names: generated sources,
     * third-party libraries, and internal types that reach the header only by transitive
     * reachability. Silencing those costs nothing — no Swift call site can depend on the spelling.
     *
     * Matching is a raw **prefix** over the declaration's FQN (SKIE: "matches a prefix of the fully
     * qualified name of declarations like classes or functions"), so these stay as narrow as the
     * thing they cover — a *new* collision in code Swift does read must still be flagged. In
     * particular there is deliberately no blanket `com.circuitstitch.deferno.core.model` entry: that
     * is the package the gate most needs to keep policing.
     */
    val SKIE_SUPPRESSED_NAME_COLLISION_PREFIXES: List<String> = listOf(
        // SQLDelight-generated `*Entity` rows (their `description` column). Generated source — there
        // is nowhere to put an annotation. They reach the header only because core:database is
        // transitively reachable from the exported shell API; Swift never names them.
        "com.circuitstitch.deferno.core.database.sql",
        // The write DTOs the create flow POSTs. Reachable through DataCreateWriter's signature;
        // Swift builds items through the shared components, never by naming a payload.
        "com.circuitstitch.deferno.core.network.dto.CreateTaskPayload",
        "com.circuitstitch.deferno.core.network.dto.CreateHabitPayload",
        "com.circuitstitch.deferno.core.network.dto.CreateChorePayload",
        "com.circuitstitch.deferno.core.network.dto.CreateEventPayload",
        // The Task command carrying a new description body — Kotlin-side domain input.
        "com.circuitstitch.deferno.core.domain.command.SetTaskDescription",
        // The Brain dump extractor's existing-item anchor. iOS-only in practice (it arrives via
        // :feature:braindumps, which macOS doesn't export), harmless on the macOS link.
        "com.circuitstitch.deferno.core.agent.ItemAnchor",
        // Ktor's `HttpStatusCode.description`.
        "io.ktor.http.HttpStatusCode",
        // kotlinx-datetime's `LocalDateProgression`/`YearMonthProgression`: the `first()`/`last()`
        // *extension functions* (top-level in `kotlinx.datetime`, hence these FQNs rather than the
        // receivers') collide with the progressions' own `first`/`last` properties. LocalDate and
        // YearMonth are Swift-facing on purpose, so the progressions come along for the ride.
        "kotlinx.datetime.first",
        "kotlinx.datetime.last",
    )
}

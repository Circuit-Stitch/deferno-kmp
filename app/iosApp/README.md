# iosApp

The iOS application entry point. Two halves live here:

- **`build.gradle.kts`** — the `:app:iosApp` Gradle module. It bundles the shared
  feature slices into a single static **`Deferno.framework`** (targets `iosX64`,
  `iosArm64`, `iosSimulatorArm64`). This half builds on any host for klib compilation;
  the framework binary links on a **macOS runner** (ADR-0006).
- **`iosApp/`** — the **Xcode project** (SwiftUI). It is created on macOS with Xcode
  and links the `Deferno` framework produced above. SwiftUI Views are centralized here,
  in per-feature folders (the deliberate Android-co-located / iOS-centralized asymmetry
  of ADR-0004). The placeholder Swift sources in `iosApp/` show the intended shape; the
  `.xcodeproj` itself is created on macOS (it can be committed once it exists, but is not
  authored from the Linux scaffold).

The framework already exports a SKIE-free stub — `IosGreeting` (in
`src/iosMain/.../ios/Umbrella.kt`) — which `ContentView.swift` reads, so the iOS shell
renders a value from the shared framework even before SKIE is wired (see below).

## Wiring the framework (done on macOS)

Everything below requires **macOS + Xcode** (Swift 5.8 / Xcode 14.3+) — the
`ready-for-human` step for issue #12. The Gradle/Swift sources are committed; only the
`.xcodeproj` and the build itself are macOS-only.

1. **Create the Xcode project.** `iosApp/iosApp.xcodeproj`, via the KMP project wizard or
   by hand, with the placeholder `iosApp/*.swift` as its sources.
2. **Make the target universal (iPhone + iPad).** Set the build setting
   **`TARGETED_DEVICE_FAMILY = 1,2`** (1 = iPhone, 2 = iPad).
3. **Add a Run Script build phase** (before *Compile Sources*, with *Based on dependency
   analysis* unchecked) that builds + embeds the framework:
   ```sh
   if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then exit 0; fi
   cd "$SRCROOT/../.."            # repo root (SRCROOT is app/iosApp)
   ./gradlew :app:iosApp:embedAndSignAppleFrameworkForXcode
   ```
   Disable **User Script Sandboxing** (`ENABLE_USER_SCRIPT_SANDBOXING = NO`) or the
   Gradle invocation is blocked. (Alternatively use `assembleDefernoXCFramework` for a
   redistributable XCFramework.)
4. **Link the framework.** Point `FRAMEWORK_SEARCH_PATHS` at
   `$(SRCROOT)/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` and add
   `-framework Deferno` to `OTHER_LDFLAGS`. Because the framework is **static**
   (`isStatic = true`), link it — do **not** embed it.
5. **`import Deferno`** from Swift (already done in `ContentView.swift`). Build + run on an
   iPhone or iPad simulator; the stub screen shows `IosGreeting().text`.

## SKIE (deferred)

ADR-0003 calls for **SKIE** to bridge `Flow`/suspend/sealed types into idiomatic Swift.
It is **not wired yet**: no released SKIE supports **Kotlin 2.4.0** as of 2026-06 (latest
0.10.12 tops out at Kotlin 2.3.10), and SKIE asserts the Kotlin version at *configuration*
time — so applying it would fail Gradle sync on every host, Linux included. The plain
Kotlin→ObjC export above works without it. When a 2.4.0-compatible SKIE ships, uncomment
the `skie` entries in `gradle/libs.versions.toml`, apply `alias(libs.plugins.skie)` in
`build.gradle.kts`, and switch the feature deps there to `export(...)`. Track
<https://github.com/touchlab/SKIE/releases>.

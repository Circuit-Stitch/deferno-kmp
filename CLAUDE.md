# Deferno (KMP)

Native Deferno client built on Kotlin Multiplatform — **Android first**, with iOS and desktop to
follow over a shared core (genuinely native UI per platform). See `CONTEXT.md` and
`docs/adr/0003`–`0004` for the architecture; the stack below describes the Android target, the
first to land.

## Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Build:** Gradle (Kotlin DSL) with a version catalog at `gradle/libs.versions.toml` and `build-logic` convention plugins
- **Min / target / compile SDK:** 26 / 35 / 35 — defined once in `build-logic` `ProjectConfig` (the source of truth)
- **Application ID:** `com.circuitstitch.deferno`
- **JDK:** 17 (toolchain). You don't need JDK 17 installed — the Gradle Daemon JVM toolchain (`gradle/gradle-daemon-jvm.properties`) and the project toolchain (`ProjectConfig.JVM_TOOLCHAIN`, applied via the convention plugins) auto-provision Eclipse Temurin 17 via the Foojay resolver. Launch Gradle with any JDK that can run Gradle 9.5.1 (17–25), e.g. Android Studio's bundled JDK.
- **Kotlin:** compiled by AGP's built-in Kotlin support (AGP 9+) — there is no `org.jetbrains.kotlin.android` plugin; only the Compose compiler plugin is applied explicitly.
- **DI:** kotlin-inject + kotlin-inject-anvil — compile-time, KMP, via KSP (ADR-0003). The scope layering (process/app → Account → scene, ADR-0008) lives in `core/di`; the `deferno.di` convention wires the runtimes + per-target KSP processors into a shared module. KSP2 is version-decoupled from the Kotlin compiler, so `ksp = 2.3.9` pairs with Kotlin 2.4.0 (no `<kotlin>-<ksp>` suffix).
- **Coverage:** Kover (ADR-0006) on every shared module via the `deferno.coverage` convention. The hard ~85–90% gate runs over the *merged* shared-core report (`deferno.coverage.aggregation`, applied at the root): CI runs `:koverVerify` on every PR. It's deliberately not wired into `check` — measure logic, not boilerplate, so generated DI/serialization code and never-instantiated DI scope markers are excluded (see `CoverageConfig`).

## Layout

Multi-module KMP per ADR-0004 (`core/*` foundations → `feature/*` slices → per-platform `app/*`):

```
build-logic/                          ← composable convention plugins — composite build
core/                                 ← shared KMP foundations
  model · common · network · database · secure · data · domain · designsystem
  di/                                 ← compile-time DI scope graph (process/app → Account → scene, ADR-0008)
  sidecar/                            ← OS-agnostic JVM Sidecar client + JSON IPC contract to native Helpers (JVM-only · deferno.jvm.library · ADR-0024/0025)
feature/                              ← shared KMP feature slices (Decompose component + ViewModel + state; commonMain + iOS)
  auth · tasks · plan
  tasks/ui · plan/ui                  ← per-slice Compose Views (deferno.compose.library: Android + JVM, NO iOS — #27)
                                          androidMain = Android-native screens · commonMain = reusable atoms
app/
  androidApp/                         ← Android application (Jetpack Compose host)
    src/main/kotlin/com/circuitstitch/deferno/
      DefernoApplication.kt           ← entry point: builds the AppComponent DI graph, loads the roster, seeds dev-PAT accounts (#68)
      MainActivity.kt                 ← single Compose-host activity (RootComponent over the AppComponent; DefernoTheme)
      shell/                          ← navigation shell: RootComponent (Auth↔Main) + Main Destination graph + AccountSession (#55/#68)
    src/test/kotlin/…/{ui,demo,shell}/ ← Compose UI + Roborazzi screenshot tests + shell tests; demo/ holds the in-memory repository test fakes
    src/main/res/                     ← resources (strings, themes, adaptive launcher icons: flame foreground + monochrome)
  desktopApp/                         ← Compose Desktop (JVM) entry point — stub for now (native desktop UI is a follow-up)
  iosApp/                             ← iOS umbrella framework (Kotlin) + Xcode project (added on macOS)
helpers/                              ← native per-OS Sidecar Helper processes (NOT Gradle modules — built/signed on their OS)
  macos/                              ← Swift launchd-activated Helper: SFSpeech + mic/Speech TCC over the IPC contract (SwiftPM · ADR-0024 · #121)
docs/adr/                             ← architecture decision records
docs/agents/                          ← agent-skill configuration (see below)
gradle/libs.versions.toml             ← single source of truth for dependency versions
```

Each `core/*` and `feature/*` module is a KMP library (Android + JVM + iOS targets) applied via the
`deferno.kmp.library` convention plugin; its own build file supplies only the android `namespace` and
its module dependencies. Kotlin sources live under `src/<sourceSet>/kotlin` (`commonMain`, `commonTest`,
`androidMain`, `iosMain`, …) — not `.../java`. The one exception is `core/designsystem`: it's a Compose
UI library (`deferno.compose.library`) targeting only the Compose platforms (Android + JVM/desktop) —
no iOS, since the iOS View is SwiftUI (ADR-0003/0004). A feature slice's **Compose Views can't live
in its logic module**: that module targets iOS, and the Compose compiler plugin is module-wide — it
would break the iOS compilation (`IncompatibleComposeRuntimeVersionException`). So each slice has a
**sibling `:feature:*:ui` submodule** (`deferno.compose.library`, Android + JVM, no iOS) holding the
Android-native screens in `androidMain` and reusable, platform-neutral atoms in `commonMain`; it
depends on `core/designsystem` from `commonMain` (#27, ADR-0004 amendment). The feature logic module
itself stays Compose-free and iOS-capable.

The convention plugins (`build-logic/src/main/kotlin/`, ADR-0004) are small and composable:

- `deferno.kmp` — cross-platform targets (`jvm()` + iOS) + JVM toolchain + `commonTest` framework.
- `deferno.android` — adds the KMP Android library target + SDK levels (composes `deferno.kmp`).
- `deferno.coverage` — Kover with the shared-core exclusions (`CoverageConfig`) on each module's own report.
- `deferno.coverage.aggregation` — the coverage *gate*: applied at the root, aggregates `core/*`+`feature/*` via `kover(project(...))` and enforces the merged ~85–90% bound (plus a "must be measured" floor so a hollowed-out gate can't pass vacuously). Run via `:koverVerify` (in CI).
- `deferno.kmp.library` — composes `deferno.android` + `deferno.coverage`; applied by every `core/*`/`feature/*` module.
- `deferno.jvm.library` — a pure-Kotlin/JVM library (kotlin-jvm + JVM toolchain + `deferno.coverage`), the no-Android/no-KMP sibling of `deferno.kmp.library` (the standalone pure-Kotlin module `deferno.kmp` anticipates). Applied by a module that is JVM-only *by design*: currently `core/sidecar`, the JVM half of the native-sidecar substrate (ADR-0024/0025) whose peers are native Helper processes (not Kotlin), so KMP targets would be permanent dead weight. Still gated by the merged coverage report (it's a `core/*` module).
- `deferno.di` — kotlin-inject + anvil DI wiring (KSP plugin + DI runtimes + per-target processors); composed onto `deferno.kmp.library` by modules that host or contribute DI bindings: `core/di` (the merge site — `AppComponent`/`AccountComponent`/`SceneComponent`) plus the modules with distributed `@ContributesTo` bindings (`core/secure · network · data · database · domain`, ADR-0014).
- `deferno.compose` — applies the two Compose Gradle plugins (Compose Multiplatform runtime + `compose {}`/Resources DSL, and the Kotlin Compose *compiler* plugin). Composed onto a module that holds Composable code; deps stay in each module's build file (commonMain for a shared UI library, `androidMain` for a feature slice's Android Views — ADR-0004).
- `deferno.compose.library` — a Compose UI *library* on the Compose platforms only: **Android + JVM/desktop, no iOS** (the iOS View is SwiftUI with its own design system, ADR-0003/0004; Compose Multiplatform 1.11 also dropped the deprecated `iosX64` variant). Sibling of `deferno.kmp.library` minus iOS, plus `deferno.compose`. Applied by `core/designsystem` and each per-slice UI submodule (`feature/tasks/ui`, `feature/plan/ui` — the Compose Views can't share a module with the slice's iOS target, ADR-0004 #27).
- `deferno.contract-fixtures` — embeds the captured `contracts/fixtures/*.json` into a module's `commonTest` as a generated `ContractFixtures` object (Gradle task `generateContractFixtures`) so the golden-envelope harness loads them on every KMP target with no runtime file IO (#19); applies no external plugin, composed onto modules that host the harness (currently `core/network`).
- `deferno.android.application` — `app/androidApp` (SDK levels + toolchain from `ProjectConfig`).
- `deferno.jvm.application` — `app/desktopApp` (Kotlin/JVM + toolchain). It deliberately omits Gradle's built-in `application` plugin: the app is Compose Desktop (ADR-0003) and `compose.desktop.application {}` supplies `run`/`mainClass`/packaging — applying both would duplicate them.

`ProjectConfig` holds the SDK levels + JVM toolchain shared across them. `app/iosApp` is a bespoke
iOS-only framework and stays a hand-written build file.

## Common commands

```sh
./gradlew build                            # build + test everything (Android/JVM; iOS klibs cross-compile)
./gradlew check                            # all unit tests: commonTest on JVM + Android host
./gradlew :core:model:jvmTest              # one module's commonTest on the JVM-fast path
./gradlew :core:model:koverHtmlReport      # coverage report for one module (Kover)
./gradlew :koverVerify                     # the merged shared-core coverage gate (what CI enforces)
./gradlew :koverHtmlReport                 # merged shared-core coverage report (build/reports/kover/html)
./gradlew :app:androidApp:assembleDebug    # build the debug APK
./gradlew :app:androidApp:installDebug     # build + install on a connected device/emulator
./gradlew :app:androidApp:connectedAndroidTest  # instrumented tests (needs a device/emulator)
./gradlew :app:androidApp:lint             # Android Lint
./gradlew :app:desktopApp:run              # run the desktop (JVM) app
```

iOS klibs cross-compile on any host, but **running iOS tests and linking the iOS framework require macOS**
(ADR-0006) — on Linux/Windows those tasks self-disable (`SKIPPED`). The first `./gradlew` run downloads
Gradle 9.5.1, a Temurin JDK 17 toolchain, Kotlin/Native, and all dependencies, so it needs network access.
Android Studio: **Open** this directory and let it sync.

## Conventions

- New dependencies go through `gradle/libs.versions.toml`, referenced as `libs.*` — don't hard-code versions in module build files.
- Shared, cross-platform code is a KMP library under `core/*` (the right layer) or a vertical slice under `feature/*`; a new shared KMP module applies the `deferno.kmp.library` convention plugin (app entry points use `deferno.android.application` / `deferno.jvm.application`). Build config (SDK levels, toolchain) belongs in `build-logic` (`ProjectConfig` + the convention plugins), not in module build files. v1 keeps granularity small (ADR-0004) — modules earn further splitting rather than being split pre-emptively.
- Keep secrets (keystores, `google-services.json`, API keys) out of git — see `.gitignore`.

## Agent skills

### Issue tracker

Issues and PRDs live as GitHub issues in `Circuit-Stitch/deferno-kmp` (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles map 1:1 to the default label strings (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`); create them on first use. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root (created lazily by `/grill-with-docs`). See `docs/agents/domain.md`.

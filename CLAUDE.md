# Deferno (Android)

Native Android client for Deferno, built with Kotlin and Jetpack Compose.

## Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Build:** Gradle (Kotlin DSL) with a version catalog at `gradle/libs.versions.toml`
- **Min / target / compile SDK:** 26 / 35 / 35
- **Application ID:** `com.circuitstitch.deferno`
- **JDK:** 17 (toolchain). Use Android Studio's bundled JDK, or a JDK 17 install, to run Gradle.

## Layout

```
app/                                  ← the application module
  src/main/kotlin/com/circuitstitch/deferno/
    DefernoApplication.kt             ← Application entry point
    MainActivity.kt                   ← single Compose-host activity
    ui/theme/                         ← Compose theme (Color/Type/Theme)
  src/main/res/                       ← resources (strings, themes, vector launcher icons)
  src/test/                           ← local JVM unit tests
  src/androidTest/                    ← instrumented (device/emulator) tests
docs/adr/                             ← architecture decision records
docs/agents/                          ← agent-skill configuration (see below)
gradle/libs.versions.toml             ← single source of truth for dependency versions
```

Kotlin sources live under `src/<set>/kotlin` (not `.../java`).

## Common commands

```sh
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # build + install on a connected device/emulator
./gradlew test                   # local unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew lint                   # Android Lint
```

The first `./gradlew` run downloads Gradle 8.11.1 and all dependencies, so it needs network access. Android Studio: **Open** this directory and let it sync.

## Conventions

- New dependencies go through `gradle/libs.versions.toml`, referenced as `libs.*` — don't hard-code versions in module build files.
- One feature → consider its own package under `com.circuitstitch.deferno`; promote to a Gradle module when it earns isolation.
- Keep secrets (keystores, `google-services.json`, API keys) out of git — see `.gitignore`.

## Agent skills

### Issue tracker

Issues and PRDs live as GitHub issues in `Circuit-Stitch/deferno-android` (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles map 1:1 to the default label strings (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`); create them on first use. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root (created lazily by `/grill-with-docs`). See `docs/agents/domain.md`.

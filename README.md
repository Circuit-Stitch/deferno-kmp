# Deferno Client

The native Deferno client — **Android first**, with iOS and desktop to follow over a shared
Kotlin Multiplatform core. The UI is genuinely native per platform (Jetpack Compose on Android,
SwiftUI on iOS, Compose Desktop on desktop); everything down through presentation and navigation
is shared Kotlin (Decompose). See [ADR-0003](docs/adr/0003-kmp-shared-presentation-native-ui.md)
and [ADR-0004](docs/adr/0004-module-structure-nia-hybrid.md) for the architecture and target
module layout. The build instructions below are for the Android target — the first to land.

## Requirements

- **Any JDK that runs Gradle 9.5.1 (JDK 17–25)** to launch the build — the Android Studio bundled
  JDK works out of the box. You don't need to install JDK 17 yourself: the build targets JDK 17 via
  a Gradle Daemon JVM toolchain (`gradle/gradle-daemon-jvm.properties`) and the project toolchain
  (`kotlin { jvmToolchain(17) }`), and auto-provisions Eclipse Temurin 17 on first run.
- **Android SDK** with platform 35 and build-tools (install via Android Studio's SDK Manager).
- A device or emulator running **Android 8.0 (API 26)** or newer.

## Getting started

The first run downloads Gradle 9.5.1, a Temurin JDK 17 toolchain, and all dependencies, so it needs
network access. Open the project in **Android Studio** (Open → select this directory) and let the
Gradle sync finish, or from the command line:

```sh
./gradlew assembleDebug     # build the debug APK
./gradlew installDebug      # install on a connected device/emulator
./gradlew test              # run local unit tests
```

If Gradle can't find your SDK, create a `local.properties` (Android Studio writes this for you):

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` is git-ignored — it's machine-specific.

## Project structure

| Path                          | Purpose                                            |
| ----------------------------- | -------------------------------------------------- |
| `app/`                        | The application module                             |
| `app/src/main/kotlin/`        | Kotlin sources (`com.circuitstitch.deferno`)       |
| `app/src/main/res/`           | Resources                                          |
| `gradle/libs.versions.toml`   | Dependency version catalog                         |
| `docs/adr/`                   | Architecture decision records                      |
| `docs/agents/`                | Agent-skill configuration                          |

> This is the initial Android scaffold. It migrates to the hybrid `core/*` · `feature/*` ·
> `app/{androidApp,iosApp,desktopApp}` layout described in
> [ADR-0004](docs/adr/0004-module-structure-nia-hybrid.md) as the shared core lands.

## Key facts

- **Architecture:** shared Kotlin Multiplatform core (models, networking, persistence, sync, presentation + navigation) with native UI per platform — see [ADR-0003](docs/adr/0003-kmp-shared-presentation-native-ui.md)
- **Application ID:** `com.circuitstitch.deferno`
- **minSdk / targetSdk / compileSdk:** 26 / 35 / 35
- **Android UI:** Jetpack Compose (Material 3)
- **Build:** Gradle 9.5.1 + AGP 9.2.1 (Kotlin compiled by AGP's built-in Kotlin support — there is no
  separate `org.jetbrains.kotlin.android` plugin)
- **Code style:** shared Kotlin `KOTLIN_OFFICIAL` settings live in `.idea/codeStyles/`

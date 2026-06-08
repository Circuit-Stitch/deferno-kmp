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
  (`ProjectConfig.JVM_TOOLCHAIN`, applied via the `build-logic` convention plugins), and
  auto-provisions Eclipse Temurin 17 on first run.
- **Android SDK** with platform 35 and build-tools (install via Android Studio's SDK Manager).
- A device or emulator running **Android 8.0 (API 26)** or newer.

## Getting started

The first run downloads Gradle 9.5.1, a Temurin JDK 17 toolchain, and all dependencies, so it needs
network access. Open the project in **Android Studio** (Open → select this directory) and let the
Gradle sync finish, or from the command line:

```sh
./gradlew build                          # build + test every module (Android/JVM; iOS klibs)
./gradlew :app:androidApp:assembleDebug  # build the debug APK
./gradlew :app:androidApp:installDebug   # install on a connected device/emulator
./gradlew check                          # run local unit tests (commonTest on JVM + Android host)
./gradlew :core:model:koverHtmlReport    # coverage report for a module (Kover, ADR-0006)
```

> iOS klibs cross-compile on any host, but running iOS tests and linking the iOS framework require
> macOS (ADR-0006); on Linux/Windows those tasks self-disable. Build the iOS app from the
> `app/iosApp` Xcode project on a Mac.

If Gradle can't find your SDK, create a `local.properties` (Android Studio writes this for you):

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` is git-ignored — it's machine-specific.

## Project structure

Multi-module Kotlin Multiplatform per [ADR-0004](docs/adr/0004-module-structure-nia-hybrid.md):

| Path                          | Purpose                                                              |
| ----------------------------- | ------------------------------------------------------------------- |
| `core/*`                      | Shared KMP foundations — `model`, `common`, `network`, `database`, `secure`, `data`, `domain`, `designsystem` |
| `feature/*`                   | Shared KMP feature slices — `auth`, `tasks`, `plan`                 |
| `app/androidApp/`             | Android application (Jetpack Compose host)                          |
| `app/desktopApp/`             | Compose Desktop (JVM) entry point                                   |
| `app/iosApp/`                 | iOS umbrella framework (Kotlin) + Xcode project (added on macOS)    |
| `build-logic/`                | Composable convention plugins (`deferno.kmp`/`.android`/`.coverage`/`.kmp.library` + app plugins) — composite build |
| `gradle/libs.versions.toml`   | Dependency version catalog                                          |
| `docs/adr/`                   | Architecture decision records                                       |
| `docs/agents/`                | Agent-skill configuration                                           |

Each `core/*` and `feature/*` module is a KMP library with `commonMain`/`commonTest` source sets and
Android, JVM, and iOS targets. The modules are scaffolded shells today (they compile and resolve);
real code lands feature by feature.

## Key facts

- **Architecture:** shared Kotlin Multiplatform core (models, networking, persistence, sync, presentation + navigation) with native UI per platform — see [ADR-0003](docs/adr/0003-kmp-shared-presentation-native-ui.md)
- **Application ID:** `com.circuitstitch.deferno`
- **minSdk / targetSdk / compileSdk:** 26 / 35 / 35
- **Android UI:** Jetpack Compose (Material 3)
- **Build:** Gradle 9.5.1 + AGP 9.2.1 (Kotlin compiled by AGP's built-in Kotlin support — there is no
  separate `org.jetbrains.kotlin.android` plugin)
- **Code style:** shared Kotlin `KOTLIN_OFFICIAL` settings live in `.idea/codeStyles/`

## License

The Deferno client is open source under the **Apache License, Version 2.0** — see
[`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) ([ADR-0020](docs/adr/0020-apache-2-open-source-client-license.md)).
The backend service is separate and proprietary; this repository is the client only.

- The **"Deferno"** name and flame branding are reserved trademarks of Circuit Stitch and are
  **not** granted by the license (Apache-2.0 §6) — forks must rebrand. See [`NOTICE`](NOTICE).
- Bundled third-party components (whisper.cpp/ggml, the Whisper model weights, SQLCipher,
  java-keyring, IBM Plex fonts, and — in the desktop installer — the JetBrains Runtime) carry their
  own licenses, aggregated in [`THIRD-PARTY-LICENSES`](THIRD-PARTY-LICENSES).

Contributions are welcome under an inbound = outbound model with a **DCO sign-off** (no CLA) —
see [`CONTRIBUTING.md`](CONTRIBUTING.md).

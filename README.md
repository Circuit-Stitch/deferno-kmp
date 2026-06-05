# Deferno for Android

Native Android client for Deferno — Kotlin + Jetpack Compose.

## Requirements

- **JDK 17** (the Android Studio bundled JDK works out of the box).
- **Android SDK** with platform 35 and build-tools (install via Android Studio's SDK Manager).
- A device or emulator running **Android 8.0 (API 26)** or newer.

## Getting started

Open the project in **Android Studio** (Open → select this directory) and let the Gradle sync finish, or from the command line:

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

## Key facts

- **Application ID:** `com.circuitstitch.deferno`
- **minSdk / targetSdk / compileSdk:** 26 / 35 / 35
- **UI:** Jetpack Compose (Material 3)

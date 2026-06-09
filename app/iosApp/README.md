# iosApp

The iOS application entry point. Two halves live here:

- **`build.gradle.kts`** — the `:app:iosApp` Gradle module. It bundles the shared
  feature slices into a single static **`Deferno.framework`** (targets `iosX64`,
  `iosArm64`, `iosSimulatorArm64`). This half builds on any host for klib compilation;
  the framework binary links on a **macOS runner** (ADR-0006).
- **`iosApp/`** — the **SwiftUI sources** (`DefernoApp.swift`, `ContentView.swift`),
  centralized in per-feature folders (the deliberate Android-co-located / iOS-centralized
  asymmetry of ADR-0004). `ContentView` reads its text from the shared framework.
- **`iosApp.xcodeproj/`** — the committed **Xcode project** (universal iPhone + iPad,
  `TARGETED_DEVICE_FAMILY = 1,2`). It links the static `Deferno` framework produced above
  and drives its build via a Gradle Run Script phase (see below). Built + run on the iOS
  17.2 simulator — iPhone *and* iPad — under Xcode 15.2 (#35).

The framework already exports a SKIE-free stub — `IosGreeting` (in
`src/iosMain/.../ios/Umbrella.kt`) — which `ContentView.swift` reads, so the iOS shell
renders a value from the shared framework even before SKIE is wired (see below).

## Building & running (macOS + Xcode)

The Xcode project is **committed** (`iosApp.xcodeproj`), so on macOS + Xcode (verified on
Xcode 15.2 / iOS 17.2 SDK) you just build and run — no project setup needed. Klibs
cross-compile on any host, but **linking the framework + running the app require macOS**
(ADR-0006).

**Xcode GUI:** open `app/iosApp/iosApp.xcodeproj`, pick an iPhone or iPad simulator, and
Run. The Run Script phase builds the shared framework via Gradle first; the stub screen
shows `IosGreeting().text`.

**Headless** (how #35 was verified, on a booted simulator):
```sh
# from app/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15' -derivedDataPath build/dd build
xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/iosApp.app
xcrun simctl launch booted com.circuitstitch.deferno
```

### How the project is wired

- **Universal** iPhone + iPad: `TARGETED_DEVICE_FAMILY = 1,2` (1 = iPhone, 2 = iPad).
- **Run Script phase**, ordered *before* Compile Sources, with *Based on dependency
  analysis* off (`alwaysOutOfDate`) and **User Script Sandboxing disabled**
  (`ENABLE_USER_SCRIPT_SANDBOXING = NO`) so Gradle can run:
  ```sh
  if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then exit 0; fi
  cd "$SRCROOT/../.."            # repo root (SRCROOT is app/iosApp)
  ./gradlew :app:iosApp:embedAndSignAppleFrameworkForXcode
  ```
  (Alternatively `assembleDefernoXCFramework` for a redistributable XCFramework.)
- **Static-framework link** (`isStatic = true` → link, don't embed):
  `FRAMEWORK_SEARCH_PATHS = $(SRCROOT)/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`
  (where the Gradle task stages the framework) and `OTHER_LDFLAGS = -framework Deferno`.
- **`import Deferno`** in `ContentView.swift` calls the framework's `IosGreeting().text`.

> The project sits at `app/iosApp/iosApp.xcodeproj`, so `$SRCROOT` = `app/iosApp` — which
> is exactly what the framework search path and the Run Script's `cd "$SRCROOT/../.."`
> (→ repo root) assume.

## SKIE (deferred)

ADR-0003 calls for **SKIE** to bridge `Flow`/suspend/sealed types into idiomatic Swift.
It is **not wired yet**: no released SKIE supports **Kotlin 2.4.0** as of 2026-06 (latest
0.10.12, 2026-05-18, tops out at Kotlin 2.3.21), and SKIE asserts the Kotlin version at *configuration*
time — so applying it would fail Gradle sync on every host, Linux included. The plain
Kotlin→ObjC export above works without it. When a 2.4.0-compatible SKIE ships, uncomment
the `skie` entries in `gradle/libs.versions.toml`, apply `alias(libs.plugins.skie)` in
`build.gradle.kts`, and switch the feature deps there to `export(...)`. Track
<https://github.com/touchlab/SKIE/releases>.

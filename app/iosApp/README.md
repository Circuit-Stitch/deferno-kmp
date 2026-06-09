# iosApp

The iOS application entry point. Two halves live here:

- **`build.gradle.kts`** — the `:app:iosApp` Gradle module. It bundles the shared
  feature slices into a single static **`Deferno.framework`** (targets `iosX64`,
  `iosArm64`, `iosSimulatorArm64`) and **`export(...)`s** the Tasks + Plan Decompose
  components, the domain model, and the Decompose/coroutines types their public API
  exposes so SwiftUI can render them. This half builds on any host for klib compilation;
  the framework binary links on a **macOS runner** (ADR-0006).
- **`iosApp/`** — the **SwiftUI sources**, centralized in per-feature folders (the
  deliberate Android-co-located / iOS-centralized asymmetry of ADR-0004): the iOS View
  layer with its own design system (`DesignSystem/`), shared atoms (`Common/`), the
  Tasks (`Tasks/`) and Plan (`Plan/`) Views, and the SKIE-free observation bridge
  (`Bridge/`). The Views are **thin renderers of the shared Decompose components** (#51).
- **`iosApp.xcodeproj/`** — the committed **Xcode project** (universal iPhone + iPad,
  `TARGETED_DEVICE_FAMILY = 1,2`, deployment target **iOS 16** for `NavigationSplitView`
  + size classes). It links the static `Deferno` framework produced above and drives its
  build via a Gradle Run Script phase (see below). Built + run on the iOS 17.2 simulator
  — iPhone *and* iPad — under Xcode 15.2 (#35).

## What the Views render (#51)

The Tasks + Plan Views render the shared components from ADR-0007:

- **Plan** — today's ordered Tasks (the calm home; "open into the Plan, not the backlog").
- **Tasks** — the co-resident **list / detail / tree** slots, laid out **universal and
  size-class-adaptive**: a side-by-side two-pane split on regular width (iPad, and
  Plus/Max iPhones in landscape), a single foreground pane on compact width (most
  iPhones). Adaptive off the horizontal size class, never a device check (ADR-0008).

The Views hold no business logic: they observe the components' state and forward intents
(tap, refresh, set working state, add to plan, show breakdown). All navigation lives in
the retained shared component (`DefaultTasksComponent` self-wires list→detail→tree).

### The demo harness (scaffold)

There is **no iOS app shell + DI yet** (the iOS analogue of the Android `#55`/`#68`
shell + roster is a follow-up). To make the Views runnable today, `DefernoApp` owns a
**`DefernoDemo`** harness (`src/iosMain/.../ios/DefernoDemo.kt`) that constructs the
*real* `DefaultTasksComponent` / `DefaultPlanComponent` over in-memory `Demo*Repository`
fakes — the iOS counterpart of the Android shell's `demo/` fixtures. Only the data source
is a fixture; the Views render genuine shared components. When the real shell lands, it
replaces the harness with DI-provided repositories (ADR-0014).

## Building & running (macOS + Xcode)

The Xcode project is **committed** (`iosApp.xcodeproj`), so on macOS + Xcode (verified on
Xcode 15.2 / iOS 17.2 SDK) you just build and run — no project setup needed. Klibs
cross-compile on any host, but **linking the framework + running the app require macOS**
(ADR-0006).

**Xcode GUI:** open `app/iosApp/iosApp.xcodeproj`, pick an iPhone or iPad simulator, and
Run. The Run Script phase builds the shared framework via Gradle first.

**Headless** (how the Views were verified, on a booted simulator):
```sh
# from app/iosApp
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15 Pro Max' -derivedDataPath build/dd build
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
- **`import Deferno`** in the Swift sources reaches the exported components, the model,
  and the bridge (`StateFlowBridge`/`ValueBridge`/`DetailSlot`/`TreeSlot`, `BridgeKt`).

> The project sits at `app/iosApp/iosApp.xcodeproj`, so `$SRCROOT` = `app/iosApp` — which
> is exactly what the framework search path and the Run Script's `cd "$SRCROOT/../.."`
> (→ repo root) assume.

## SKIE (deferred) and the hand-written bridge

ADR-0003 calls for **SKIE** to bridge `Flow`/suspend/sealed types into idiomatic Swift.
It is **still not wired**: no released SKIE supports **Kotlin 2.4.0** as of 2026-06 (latest
0.10.12, 2026-05-18, tops out at Kotlin 2.3.21), and SKIE asserts the Kotlin version at
*configuration* time — so applying it would fail Gradle sync on every host, Linux included.

Until it ships, the Views observe shared state through a small **SKIE-free bridge**
(`src/iosMain/.../ios/bridge/Bridge.kt` + `iosApp/Bridge/ObservableState.swift`): concrete
wrappers turn each component `StateFlow`/Decompose `Value` into a callback-based
subscription that a SwiftUI `ObservableObject` consumes, publishing on the main thread.
When a 2.4.0-compatible SKIE ships, **delete the bridge**, uncomment the `skie` entries in
`gradle/libs.versions.toml`, apply `alias(libs.plugins.skie)` in `build.gradle.kts`, and
the Views can observe the components' `StateFlow`/`Value` directly. Track
<https://github.com/touchlab/SKIE/releases>.

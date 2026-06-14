# iosApp

The iOS application entry point. Two halves live here:

- **`build.gradle.kts`** — the `:app:iosApp` Gradle module. It bundles the shared **app
  shell** (`:app:shell`) + the DI graph (`:core:di`) + every feature slice into a single
  static **`Deferno.framework`** (targets `iosArm64`, `iosSimulatorArm64` — `iosX64`/Intel-Mac
  simulator dropped to match the shared modules' target set; see `deferno.kmp.gradle.kts`) and
  **`export(...)`s** the shell + feature Decompose components, the domain model, and the
  Decompose/coroutines/datetime types their public API exposes so SwiftUI can render them.
  This half builds on any host for klib compilation; the framework binary links on a
  **macOS runner** (ADR-0006).
- **`iosApp/`** — the **SwiftUI sources**, centralized in per-feature folders (the
  deliberate Android-co-located / iOS-centralized asymmetry of ADR-0004): the iOS View
  layer with its own design system (`DesignSystem/` — `DefernoTheme` mirrors the Compose
  palettes, driven live off `RootComponent.themeSettings`), shared atoms (`Common/`), the
  navigation frame (`RootView`, `Shell/MainShellView`), the Auth/sign-in (`Auth/`), the
  five Destinations (`Plan/`, `Calendar/`, `Tasks/`, `Profile/`, `Settings/`), the
  Search/New overlays (`Search/`, `New/`), and the SKIE-free observation bridge
  (`Bridge/`). The Views are **thin renderers of the shared Decompose components**
  (#51/#35).
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

### The app entry — real shell over the DI graph (#35)

`DefernoApp` (`@main`) now owns **`DefernoRoot`** (`src/iosMain/.../ios/DefernoRoot.kt`) —
the iOS analogue of Android's `DefernoApplication` + `MainActivity` in one host object. It
builds the process-global **`AppComponent`** from the real DI graph (`createAppComponent`),
hydrates the persisted account roster + seeds any optional dev-PAT Accounts (from the
`DevAccounts` / `DevStagingToken` Info.plist keys, both absent in a normal build), and
constructs **`DefaultRootComponent`** over an Essenty `LifecycleRegistry` with the iOS host
deep-links (`UIApplication`). SwiftUI's `RootView` renders that shared `RootComponent`
(Auth ↔ Main → the Destination graph, ADR-0013/0017): paste a PAT to sign in, then the
reveal-drawer chrome (`MainShellView`, the SwiftUI twin of the shared `ShellChrome`) over the
five Destinations + the Search/New overlays.

The in-memory **`DefernoDemo`** harness (`ios/DefernoDemo.kt`) is **retained for the unit
tests only** (`StateBridgeTests` drives it without a DB); it is no longer the app entry.

#### Skip the sign-in screen in dev (seed a staging account)

`DefernoRoot.seedDevAccounts()` reads `DevStagingToken` from `Info.plist`; a non-empty value
auto-seeds a "Dev (staging)" account so a **Debug** build opens straight to the shell. The
value comes from the `DEV_STAGING_TOKEN` build setting, wired so the secret lives **on disk in
the source tree, never in git, and survives a simulator erase** (the Keychain doesn't):

- `Local.xcconfig` (committed) is the Debug base config — it `#include`s the CocoaPods config
  and then `#include?`s an optional, git-ignored `Secrets.xcconfig`.
- **Put your token in `app/iosApp/Secrets.xcconfig`** (git-ignored; create it from the comment
  block if missing): `DEV_STAGING_TOKEN = <your staging PAT>`, then rebuild.
- `Info.plist` carries `DevStagingToken = $(DEV_STAGING_TOKEN)`; empty (no `Secrets.xcconfig`,
  CI, or Release — which pins it empty) → nothing seeded, so the normal sign-in screen shows.

`pod install` keeps quiet because `Local.xcconfig` includes the pod config; if it ever resets
the Debug base config back to the pod xcconfig, re-point it at `Local.xcconfig`.

### Data layer: SQLCipher — encrypted at rest (ADR-0009)

The per-Account database is opened by `IosSqlDriverFactory` (SQLiter / `native-driver`) with SQLiter's
`Encryption` config, which applies the per-Account key via `PRAGMA key` — so the app must link a SQLite
that understands it. That's **SQLCipher**, integrated as a **CocoaPod** (`Podfile` → `pod 'SQLCipher',
'~> 4.6'`). It supplies the standard `sqlite3_*` symbols the static framework references — SQLiter's
cinterop links system sqlite at the *klib* level, but Kotlin/Native does **not** propagate that linker
opt into the consuming app for a static framework, so nothing else links sqlite and SQLCipher is the
**sole** provider (no duplicate symbols) — **plus** real encryption-at-rest: the pod's `sqlite3.c`
compiles with `SQLITE_HAS_CODEC` + `SQLCIPHER_CRYPTO_CC` (the CommonCrypto provider; no OpenSSL), so the
app binary carries `sqlcipherCodecAttach` and `PRAGMA key` is real.

After `pod install`, **build the generated `iosApp.xcworkspace`, not the bare `.xcodeproj`** (a project
with an integrated pod links only from its workspace). `Podfile` + `Podfile.lock` are committed (the
lock pins the resolved pod version so local + CI match); the integrated `Pods/` and `iosApp.xcworkspace`
are reproduced by `pod install` and are gitignored.

> **Local Ruby caveat — this dev machine only (Intel Ventura).** CocoaPods is a Ruby gem, and the macOS
> *system* Ruby (2.6) is too old for it (modern CocoaPods' `ffi` needs Ruby ≥ 3.0), while Homebrew can't
> install a Ruby *bottle* on Intel Ventura — `brew install ruby/cocoapods` falls back to building LLVM +
> Rust + Ruby **from source** (hours). The one-time fix used here, which leaves the system Ruby untouched:
> ```sh
> rbenv install 3.3.11            # compiles ONLY Ruby (~10 min), not the brew toolchain
> rbenv shell 3.3.11 && gem install cocoapods
> # that Ruby links Homebrew openssl@3, whose CA path is unpopulated → point it at a bundle:
> ln -sf /usr/local/etc/ca-certificates/cert.pem /usr/local/etc/openssl@3/cert.pem
> LANG=en_US.UTF-8 pod install   # CocoaPods needs a UTF-8 locale
> ```
> **CI needs none of this** — GitHub's macOS runners ship Ruby 3 + CocoaPods preinstalled, so the iOS
> workflow (`.github/workflows/ios.yml`) just runs `pod install`.

## Building & running (macOS + Xcode)

The Xcode project is **committed** (`iosApp.xcodeproj`), verified on Xcode 15.2 / iOS 17.2 SDK. The one
setup step is **`pod install`** (SQLCipher, above) — it generates `iosApp.xcworkspace`, which you build
from then on. Klibs cross-compile on any host, but **linking the framework + running the app require
macOS** (ADR-0006).

**Xcode GUI:** run `pod install` once (from `app/iosApp`), then open `app/iosApp/iosApp.xcworkspace`
(**not** the `.xcodeproj`), pick an iPhone or iPad simulator, and Run. The Run Script phase builds the
shared framework via Gradle first.

**Headless** (how the app was verified, on a booted simulator). Note: **do not pass
`CODE_SIGNING_ALLOWED=NO`** when you intend to *run* the app — an unsigned iOS app has no
entitlements, so the Keychain (`SecItem*`, used by the token `SecretVault` + the SQLCipher
`DatabaseKeyProvider`, ADR-0009) returns `errSecMissingEntitlement (-34018)` and sign-in aborts. The
default ad-hoc "Sign to Run Locally" signature is enough for the Simulator Keychain. (`CODE_SIGNING_ALLOWED=NO`
is fine for a *compile-only* check or the unit tests, which use the in-memory `DefernoDemo` — that's
what the iOS CI does.) The product is `Deferno.app` (`PRODUCT_NAME = Deferno`; the target/scheme are
still named `iosApp`).
```sh
# from app/iosApp
pod install   # once; regenerate after a Podfile change (see the Ruby caveat above)
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15' -derivedDataPath build/dd build
xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Deferno.app
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
- **Branding** — the app wears the Deferno **flame** throughout, all rasterized from the shared
  `core/designsystem/brand/flame.svg` (the source of truth) by `scripts/generate-brand-assets.sh`
  (macOS). The script rasterizes the SVG with **`NSImage`**, which decodes it natively *and keeps the
  transparent background* — `qlmanage`/QuickLook flattens the transparent page onto opaque white,
  which would leave a white box behind the flame. It emits two assets into `iosApp/Assets.xcassets`:
    - `AppIcon.appiconset/AppIcon-1024.png` — the home-screen icon (`ASSETCATALOG_COMPILER_APPICON_NAME
      = AppIcon`): the flame on the Deferno dark surface (`#1F1B16`), matching the Android adaptive
      icon. iOS app icons **must** be raster PNG (the catalog doesn't accept SVG; the vector Icon
      Composer `.icon` needs Xcode 16+, and this project targets 15.2), so the SVG is rasterized once
      and iOS derives every size from the 1024. No alpha (App Store requirement).
    - `Flame.imageset/Flame.png` — the bare flame on a transparent background, shared by the launch
      screen and the in-app `Brandmark` (the flame beside the Plan "Today" header — `CommonViews.swift`,
      enabled via `PaneHeader(showsBrand:)`).
  The launch screen is `LaunchScreen.storyboard` (`INFOPLIST_KEY_UILaunchStoryboardName = LaunchScreen`):
  the flame centered on the same `#1F1B16` surface. The home-screen name is **Deferno**
  (`INFOPLIST_KEY_CFBundleDisplayName`), not the `iosApp` target name. Note: the iOS Simulator caches
  the launch image aggressively per bundle id — after changing the launch screen, a fresh/erased
  simulator (or a real device) shows the new render; reinstalling onto the same booted sim may keep
  serving the old one.

> The project sits at `app/iosApp/iosApp.xcodeproj`, so `$SRCROOT` = `app/iosApp` — which
> is exactly what the framework search path and the Run Script's `cd "$SRCROOT/../.."`
> (→ repo root) assume.

## Tests (#28)

Unit coverage for the iOS View layer lives in the **`iosAppTests`** target — a host-app
unit-test bundle (`@testable import iosApp` + `import Deferno`), wired into the `iosApp`
scheme's Test action. Two suites, both green on the iOS 17.2 simulator under Xcode 15.2:

- **`SecondarySlotTests`** — the secondary-pane precedence (`resolveSecondarySlot`,
  `Common/CommonViews.swift`). Because the Compose UI module can't target iOS (ADR-0004 /
  #27), that rule is **hand-ported** from the shared Kotlin `resolveSecondarySlot`; this is
  the Swift twin of the Kotlin `SecondarySlotTest` 12-row table, so the two copies can't
  drift.
- **`StateBridgeTests`** — the SKIE-free bridge end to end: it drives the *real* `DefernoDemo`
  components and asserts list state and the co-resident **detail** slot reach the SwiftUI
  observers on the main thread (a row tap → the shared component opens the detail slot).

```sh
# from app/iosApp — builds the shared framework + app, then runs the suite on a simulator
pod install   # once (generates iosApp.xcworkspace)
xcodebuild test -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

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

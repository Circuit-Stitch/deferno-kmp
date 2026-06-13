# app/macosApp — native macOS SwiftUI app (ADR-0029)

The macOS twin of `app/iosApp`: a bespoke Kotlin/Native **`macosArm64`** framework module + a native
SwiftUI Xcode app that links it. Distinct from the Compose Desktop JVM app (`app/desktopApp`), which
stays. Mac Catalyst can't link a Kotlin/Native iOS framework (no `macabi` slice), so this is a real
macOS target (ADR-0029).

## Layout

- `build.gradle.kts` — the bespoke framework module (`macosArm64`, static `Deferno.framework`, the same
  `export(...)` list as iosApp). Mirrors iosApp; applies no convention plugin (ADR-0004).
- `src/macosMain/kotlin/.../macos/`
  - `DefernoDemoRoot.kt` — **Phase 1** demo host: builds the real `DefaultRootComponent` over in-memory
    fakes (no backend, no DI graph, no encrypted DB), seeded with one Active Account so the app opens on
    the Main shell. The macOS analogue of iOS's `DefernoRoot`, but fixture-backed (the iOS `DefernoDemo`
    precedent). Phase 1b swaps in the real `DefernoRoot` over the DI graph + paste-PAT sign-in.
  - `bridge/` — the hand-written **SKIE-free bridge** (copied from iosApp; pure Kotlin/Decompose, no
    UIKit), observed by the SwiftUI Views until SKIE supports Kotlin 2.4.0.
  - `DefernoDemo.kt`, `demo/` — the Plan+Tasks demo components + in-memory repositories.
- `macosApp/` — the SwiftUI sources (copied from iosApp; per ADR-0028 the View bodies may diverge for
  macOS). macOS-only fixes vs iOS: system background colors → `NSColor`, no `textInputAutocapitalization`,
  no size classes (always the wide layout), and `SQLiteExtensionShims.swift` (see below).
- `project.yml` — the **XcodeGen** spec (source of truth). The `.xcodeproj` is generated, not committed.

## Build & run

```sh
cd app/macosApp
xcodegen generate                                              # regenerate macosApp.xcodeproj from project.yml
xcodebuild -project macosApp.xcodeproj -scheme macosApp \
  -configuration Debug -destination 'platform=macOS,arch=arm64' build
open "$(xcodebuild -project macosApp.xcodeproj -scheme macosApp -showBuildSettings \
  | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{d=$2} / FULL_PRODUCT_NAME /{n=$2} END{print d"/"n}')"
```

A pre-build phase runs `./gradlew :app:macosApp:embedAndSignAppleFrameworkForXcode`, which stages the
Kotlin framework into `build/xcode-frameworks` (where `FRAMEWORK_SEARCH_PATHS` points). To iterate on the
Kotlin side alone: `./gradlew :app:macosApp:linkDebugFrameworkMacosArm64`.

## Phase 2 — in-process dictation (ADR-0029)

The New surface's mic dictates **on-device, in-process**: `macosApp/Speech/MacDictation.swift` implements the
shared Kotlin `NativeDictation` port over `SidecarKit.SpeechTranscriber` (the same Swift the launchd Helper
serves over a socket, ADR-0024 — but called directly here, no Helper). Kotlin owns the `Flow`
(`src/macosMain/.../speech/NativeDictation.kt` adapts it to the `core:speech` `SpeechToText` seam); Swift just
opens the mic and pushes Transcript text. `helpers/macos` is linked as a **local SwiftPM dependency** (its
`Package.swift` now exposes a `SidecarKit` library product). TCC is attributed to **this app's own identity**:
the mic/Speech usage strings live in `project.yml` (`INFOPLIST_KEY_NS*UsageDescription`), and the app is not
sandboxed (ad-hoc dev signing), so no entitlement is needed. To dictate: open New → tap the mic → grant
Speech + Microphone → speak; partials stream into the field, settling on a final.

## Notes / Phase-1 shortcuts

- **No CocoaPods** (unlike iosApp's SQLCipher): the Phase-1 demo opens no DB, so the system `-lsqlite3`
  satisfies the statically-linked SQLiter symbols. The two extension-loader symbols macOS omits
  (`SQLITE_OMIT_LOAD_EXTENSION`) are stubbed in `SQLiteExtensionShims.swift` (never called — no DB opens).
  Phase 1b links a full SQLite (e.g. SQLCipher) for the real encrypted per-Account DB.
- Ad-hoc code signing for local dev; TCC/notarization signing (Phase 2+) is a Mac-session concern kept
  out of git (ADR-0009/0029).

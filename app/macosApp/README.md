# app/macosApp — native macOS SwiftUI app (ADR-0029)

The macOS twin of `app/iosApp`: a bespoke Kotlin/Native **`macosArm64`** framework module + a native
SwiftUI Xcode app that links it. Distinct from the Compose Desktop JVM app (`app/desktopApp`), which
stays. Mac Catalyst can't link a Kotlin/Native iOS framework (no `macabi` slice), so this is a real
macOS target (ADR-0029).

## Layout

- `build.gradle.kts` — the bespoke framework module (`macosArm64`, static `Deferno.framework`, the same
  `export(...)` list as iosApp). Mirrors iosApp; applies no convention plugin (ADR-0004).
- `src/macosMain/kotlin/.../macos/`
  - `DefernoRoot.kt` — the app host (**Phase 1b**): builds the process-global `AppComponent` over the real
    DI graph (network + Keychain + roster) and constructs `DefaultRootComponent`, the macOS analogue of
    iOS's `DefernoRoot` / Android's `DefernoApplication` + `MainActivity` (AppKit instead of UIKit). Opens
    on the Auth shell; a pasted staging PAT flips the Active Account and the Main shell renders over the
    real data layer. Retains the Phase-2 `dictation` + Phase-3 `inference`/`draftTasks` seams.
  - `bridge/` — the hand-written **SKIE-free bridge** (copied from iosApp; pure Kotlin/Decompose, no
    UIKit), observed by the SwiftUI Views until SKIE supports Kotlin 2.4.0.
  - `TasksRoot.kt` — the Swift-facing Tasks + Plan Destination roots (flatten the Decompose generics for
    SwiftUI; built by `ShellBridge`).
- `macosApp/` — the SwiftUI sources (copied from iosApp; per ADR-0028 the View bodies may diverge for
  macOS). macOS-only fixes vs iOS: system background colors → `NSColor`, no `textInputAutocapitalization`,
  no size classes (always the wide layout), and `SQLiteLegacyShims.swift` (see below).
- `project.yml` — the **XcodeGen** spec (source of truth). The `.xcodeproj` is generated, not committed.

## Build & run

```sh
cd app/macosApp
./build.sh           # xcodegen generate + pod install + xcodebuild
./build.sh --open    # …then launch the built .app
```

XcodeGen generates the `.xcodeproj`; CocoaPods (SQLCipher) layers a `.xcworkspace` on top, so it's a
two-step setup — `pod install` must follow every `xcodegen generate` (regeneration drops the pod
integration), and the **workspace** is what gets built. `build.sh` wraps exactly that; the equivalent
by hand:

```sh
xcodegen generate                                              # regenerate macosApp.xcodeproj from project.yml
pod install                                                    # integrate SQLCipher → macosApp.xcworkspace
xcodebuild -workspace macosApp.xcworkspace -scheme macosApp \
  -configuration Debug -destination 'platform=macOS,arch=arm64' build
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

## Phase 3 — on-device inference (Apple Intelligence, ADR-0029)

The Brain-dump **Extractor** (transcript → draft Tasks, propose-only) runs **on-device, in-process** over
Apple Intelligence's Foundation Models: `macosApp/AI/MacInference.swift` implements the shared Kotlin
`NativeInference` port (`src/macosMain/.../agent/NativeInference.kt`) with a `LanguageModelSession`. The
model runs fully on-device, so the transcript never leaves the Mac (ADR-0009/0027); only JSON text crosses
the seam. **Kotlin keeps ownership of the schema and validation** — `NativeInferenceEngine` decodes the
model's reply against the request's `InferenceSchema` (`core:agent`), so the propose-only contract and the
typed `InferenceResult` failure modes are unchanged.

Two `core:agent` pieces make a small on-device model fit a strict Kotlin schema (both generic, derived from
the serializer descriptor — no Extractor-specific knowledge):

- **`InferenceSchema.jsonSkeleton()`** emits a by-example shape (`{"drafts":[{"id":"string",…}]}`) that
  steers the model to the exact keys + types, so it doesn't guess the root key or use integer ids.
- **`InferenceSchema.parse()` → `coerceToSchema()`** adapts the model's natural JSON to the schema: a
  datetime in a date field is trimmed to the calendar date, a quoted `"0.8"` is unquoted to a number, and a
  `"none"` placeholder in a nullable field becomes `null`. Anything still invalid is the typed
  `MalformedOutput`, never a crash.

FoundationModels is macOS 26+; the app deploys to 14.0, so it is **weak-linked** (`-weak_framework
FoundationModels` in `project.yml`) and every use is `@available(macOS 26, *)`-guarded + gated on
`SystemLanguageModel.availability` — on an older Mac (or one without Apple Intelligence) the seam answers
`NotConfigured` and nothing runs. **Try it:** launch → menu **Apple Intelligence ▸ Extract Draft Tasks…**
(⌘⇧E) → edit the sample transcript → **Extract** → the proposed drafts list (nothing is committed). This is
a macOS-app dev surface, not yet a shipped product flow; the real DI binding (`MacosAgentBindings`) injects
the same engine once the engine-choice App setting lands (#150 / Phase 1b).

## Phase 1b — real DI graph + paste-PAT sign-in (ADR-0029, #188)

`DefernoApp.swift` hosts `DefernoRoot` over the real DI graph. **Try it:** launch → the **Auth shell** →
**Use a token instead** → paste a staging PAT (ADR-0023) → the Active Account flips → the **Main shell**
renders over the real data layer → **Profile** loads the `/auth/me` staging identity. Optional dev-PAT
seeding: add `DevAccounts` + `DevStagingToken` string keys to `macosApp/Info.plist` locally (kept out of
git) to open on real staging data without typing.

- **SQLCipher via CocoaPods** — the SAME pod `app/iosApp` uses (Zetetic's stock `SQLCipher ~> 4.6`), so
  macOS and iOS link an identical, proven build. It exports the standard `sqlite3_*` symbols the shared
  SQLiter driver references (including the deprecated ones the cinterop still wraps but never calls), so
  there's no shim. It is the **sole** sqlite provider (the app links no `-lsqlite3`, so no duplicate
  symbols) plus real `PRAGMA key` encryption-at-rest (ADR-0009) — so the moment an Account goes active,
  the encrypted per-Account DB opens. (SwiftPM's skiptools/swift-sqlcipher was tried first per #188 but
  its amalgamation drops deprecated APIs under `SQLITE_OMIT_DEPRECATED`, which would have needed link-time
  stubs — CocoaPods avoids that, matching iOS exactly. SidecarKit stays a local SwiftPM package.)
- The custom URL scheme (`com.circuitstitch.deferno`, for the OAuth redirect fallback / #189) is registered
  via a partial `macosApp/Info.plist` merged into the generated one (`GENERATE_INFOPLIST_FILE` stays YES).
- Ad-hoc code signing for local dev; TCC/notarization signing is a Mac-session concern kept out of git
  (ADR-0009/0029).

# Native macOS app: a real Kotlin/Native `macosArm64` target, SwiftUI Views, in-process capabilities

**Context.** ADR-0003 fixed the desktop View to **Compose Desktop on the JVM** ("Only the *View* is
native (Jetpack Compose on Android, SwiftUI on iOS, **Compose Desktop on desktop**)"), and ADR-0024
reached macOS-native capabilities through an **out-of-process** launchd Swift Helper precisely
*because* "the desktop client is **Compose Desktop on the JVM** … there is no Kotlin/Native `macos*`
target … so Java's FFM / Project Panama … is not available" — the JVM client "connects to a
well-known socket **path**" and the helper hosts `SFSpeechRecognizer`, EventKit, Notification Center,
the menu-bar status item, and global hotkeys over a Unix socket. ADR-0027 made client-side inference
**tiered** — "local preferred when available, off-device only by explicit opt-in, never silent" — and
shipped **hosted-only** (the Koog Anthropic-format relay) as the v1 floor, with local engines to
follow per platform.

We now want a **genuinely-native SwiftUI/AppKit macOS app**, distinct from the Compose Desktop JVM
app, to iterate on tight macOS integration: in-process native capabilities and **Apple Intelligence
(Foundation Models)** on-device inference. A Swift app *can* call SFSpeech/EventKit/FoundationModels
directly — the JVM-can't-FFI premise that justified the out-of-process Helper evaporates for it. The
existing Compose Desktop JVM app **stays** (it's the cross-platform desktop target and the Helper's
client); this ADR adds a second macOS surface, it does not replace the first.

**Decision.** Three decisions, each a *per-target* split of a prior ADR (for macOS only), not a
wholesale reversal.

1. **A real Kotlin/Native `macosArm64` target** across the shared modules (added to the `deferno.kmp`
   convention), feeding a **bespoke `macosArm64` `Deferno.framework`** that the SwiftUI Xcode app
   links — mirroring `app/iosApp`. **Mac Catalyst is rejected:** Kotlin/Native emits no `macabi`
   slice and has no `macCatalyst` target, so a Catalyst app *cannot* link a Kotlin/Native iOS
   framework — a native Mac app needs a real macОS framework, full stop. This **amends ADR-0003** for
   macOS: the macOS *View* is SwiftUI (like iOS), not Compose Desktop. The shared Compose-free shell
   (ADR-0017) is unchanged — it gains one more render surface, it is not forked. Most Apple platform
   code (Darwin Ktor engine, SQLDelight native driver + SQLiter encryption, Keychain vault,
   `NWPathMonitor`, `NSUserDefaults`) is **shared with iOS via the default-hierarchy `appleMain`
   source set**; only two pieces genuinely diverge iOS↔macOS and are isolated per source set: the
   logging backend and the Koog inference engine (see Consequences). The faster spike — render the
   shell over the in-memory `DefernoDemo` scaffold before wiring the real DI graph — keeps Phase 1
   off `core/network`/`database`/`agent` until sign-in lands (Phase 1b).

2. **Native capabilities run in-process**, reusing the Swift `SidecarKit` sources from `helpers/macos`
   as a local SwiftPM dependency (`SpeechTranscriber`, `SidecarPermissions`, `StatusItemController`,
   `HotkeyCenter`) — no Unix socket, no launchd, for the native app. This **amends ADR-0024** for the
   SwiftUI app: its whole rationale was "most of these have **no pure-JVM API** … not JVM→Obj-C FFI";
   a Swift app calls them directly, so the out-of-process broker is unnecessary overhead *here*. The
   launchd Helper + the `core/sidecar` JVM client + the multi-OS sidecar substrate (ADR-0024/0025)
   **remain** — they still serve the Compose Desktop JVM app and the Windows/Linux roadmap. TCC is now
   attributed to the **app's own** Developer-ID identity (its own `NS*UsageDescription` strings +
   entitlements), the same in-app permission posture ADR-0024 introduced, minus the broker hop.

3. **Foundation Models is a new opt-in local inference tier**, implemented in **Swift** (its
   `@Generable`/`LanguageModelSession` guided-generation API is Swift-only and not cleanly reachable
   from Kotlin/Native) and injected into the Kotlin DI bootstrap as a callback — the way `DefernoRoot`
   injects `accountSession`. The **Kotlin `InferenceEngine` seam keeps ownership of the schema and
   validation** (`InferenceSchema.serializer` derives the prompt schema and decodes/validates the
   reply, with the existing typed `InferenceResult` failure modes), so ADR-0027's **propose-only**
   contract and "never silent" invariant hold unchanged: the Swift engine returns a *proposal* decoded
   against the same schema, acceptance still commits through the ordinary Command path, and the engine
   is bound only when the person opts in (an App setting). Gate on `SystemLanguageModel.availability`;
   when unavailable, degrade to the **hosted relay floor**. This realises ADR-0027's "local engines
   follow … iOS deferred with its app" for the Mac, without changing the seam.

**Consequences.** Adding `macosArm64` surfaced two third-party gaps, both contained:

- **`amzn/kmp-logger` 0.0.1 publishes no `macosArm64` klib** and was `api`-exposed from `core/common`,
  so every shared module failed to resolve on macOS (the same constraint that already dropped
  `iosX64`). Resolved by introducing the project's **one uniform logging facade**
  (`core.common.log.Logger`/`LogLevel`/`Any.logger`, mirroring kmp-logger's API): Android/JVM/iOS
  delegate to kmp-logger; macOS writes to `os_log` directly (kmp-logger's sole platform seam is an
  `internal expect writeLog` a consumer can't supply, but the behaviour is ~20 lines). Call sites are
  byte-identical across platforms — this is now the single logging API the whole repo uses (the four
  app entry points are migrated to it). When upstream ships the klib, the macOS actual collapses.
- **Koog publishes no `macosArm64` klib** and lived in `core/agent` `commonMain`. The seam
  (`InferenceEngine`/`InferenceRequest`/`InferenceResult`/`InferenceSchema`/`Extractor`) stays
  `commonMain`; the Koog-backed `KoogInferenceEngine` + its `AgentBindings` move to a shared
  `src/hosted` directory compiled into Android/JVM/iOS only (a `srcDir` shared across the three
  targets, which avoids both triple-duplication and a custom intermediate source set that would fight
  KMP's default hierarchy template). macOS binds the `NotConfiguredInferenceEngine` floor until the
  Phase-3 Swift engine lands.

The cost is a `macosArm64` variant on every `core/*`/`feature/*` module (klibs cross-compile on any
host; linking the framework needs a Mac, ADR-0006), the `deferno.di` convention gaining a
`kspMacosArm64` processor config (anvil emits each merged component's `create()` per target via
expect/actual), and two `core/data` pieces that genuinely differ on macOS (a static `DeviceName` and
a Phase-0 `MacBrowserAuthenticator` stub; the real host name + `ASWebAuthenticationSession`/`NSWindow`
leg arrive with sign-in in Phase 1b). The macOS framework module stays **bespoke** like `app/iosApp`
— a single Apple-only framework with a unique target set and `export(...)` list doesn't earn a
convention plugin (ADR-0004's reasoning, restated for macOS).

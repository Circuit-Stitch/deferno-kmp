# Native macOS capabilities via a launchd-activated Swift sidecar

**Context.** The desktop client is **Compose Desktop on the JVM** (ADR-0003) — there is no
Kotlin/Native `macos*` target — and the toolchain is pinned to **JDK 17** (`ProjectConfig`), so
Java's FFM / Project Panama (`java.lang.foreign`, stable only on 22+) is not available. We want to
take advantage of **macOS-native features and integrate with macOS permissions (TCC)**: on-device
dictation through Apple's recognizer, the mic/Speech/Calendar/Notification permission prompts, the
Notification Center, EventKit, a menu-bar status item, and global hotkeys. Most of these have **no
pure-JVM API**. This also **reverses a documented desktop posture**: today the app does *no* in-app
permission UX on desktop (`Main.kt` — `onOpenOsAppSettings` is "Android-only … no per-app OS settings
screen on desktop"; `NewDesktopScreen` — "the OS gates the mic, not an in-app prompt"). ADR-0018
already anticipated a macOS **native STT fast path** (it named `SpeechTranscriber`) plugging into the
shared `SpeechToTextSelector` at the [[Transcript]] altitude. The primary dev machine is a **2019
Intel MacBook Pro**, and the goal is to do **as much as possible on Linux** so the Mac session is a
short build/sign/verify step.

**Decision.** Reach macOS-native capabilities through a **Developer-ID-signed Swift sidecar helper**,
not JVM→Obj-C FFI.

- **Transport:** a **Unix domain socket carrying JSON** — request/response (`queryPermission`,
  `postNotification`, `listCalendars`, `registerHotkey`), server→client **streams** (the
  `TranscriptEvent` Partial/Final/Error stream), and unsolicited **push** (permission changes, hotkey
  fires, status-item clicks). The socket brokers privacy-critical [[Transcript]]s, so the peer is
  **authenticated** — `getpeereid` uid check + 0600 perms + an in-band token (ADR-0009).
- **Lifecycle:** **launchd on-demand socket activation** — a per-user LaunchAgent; launchd owns the
  socket and spawns the helper on first connect (`launch_activate_socket()`). On-demand is not
  install-free: Conveyor (or first run) installs + registers the plist and cleans it up on uninstall.
- **The JVM client stays launchd-agnostic** — it connects to a well-known socket **path**; launchd
  provides the far end on macOS, and a **stub binder** provides it in Linux tests/dev, so the entire
  JVM side and UX are exercised end-to-end without a Mac.
- **Dictation:** the helper hosts **`SFSpeechRecognizer` (on-device)** — it runs on the 2019 Intel
  (macOS 10.15+), unlike the macOS-26-only `SpeechTranscriber` ADR-0018 named. It is a higher-rank,
  **vertically integrated** `SpeechToText` engine: it owns its **own AVAudioEngine mic** and emits
  **`TranscriptEvent`s (text), not PCM**, over the socket. **whisper-in-JVM stays the always-available
  floor**; `SpeechToTextSelector` picks the sidecar only when it reports genuinely available.
- **TCC is attributed to the helper's own identity** — it carries its **own Info.plist
  `NS*UsageDescription` strings + entitlements**, and its grants key to its (stable, Developer-ID)
  signature. macOS gains **in-app permission UX** (introspect → prompt → deep-link to Settings),
  reversing the desktop no-in-app-permission posture **for macOS only**; Linux/Windows are unchanged.
- **Signing now:** enroll in **Apple Developer / Developer ID** immediately (closes #104) — stable
  signature → persistent TCC grants and notarized distribution, matching ADR-0021's pipeline.

**Scope (v1).** v1 ships **Tier 0 + Tier 1**: the pure-JVM macOS permission polish (wire the shared
`DictationStatus.Permission*` states on macOS, the `Desktop.browse("x-apple.systempreferences:…")`
Settings deep-link, gentle denial UX) and the AWT-native chrome (About/Preferences/Quit handlers, dock
badge); **plus** the launchd Swift sidecar + `SidecarSpeechToText` (SFSpeechRecognizer) and the
**mic + Speech** TCC brokering it rides on. **Deferred:** Notification Center, EventKit/calendar, the
NSStatusItem menu-bar extra, global hotkeys, and App Intents — each additive over the same socket
contract, so they grow the helper without reshaping it.

**Considered & rejected.**
- **JNA / rococoa-style JVM→Obj-C FFI** — authorable on Linux but the tooling is largely unmaintained
  and *nothing* can be verified without a Mac; every binding is blind.
- **FFM / Panama** — would force a project-wide JDK 22+ toolchain bump and still needs a Mac to verify.
- **Pure-JVM only** — covers mic capture (`javax.sound`), menu/dock (`java.awt.Desktop`/`Taskbar`),
  and the Settings deep-link, but cannot reach SFSpeechRecognizer, UNUserNotificationCenter, EventKit,
  or NSStatusItem.
- **whisper.cpp in the sidecar / streaming PCM to a JVM engine** — the **audio/PCM-level seam ADR-0018
  explicitly rejects**; it would duplicate the engine and add a third whisper build for no v1 gain.
- **`SpeechTranscriber`** — best quality but macOS 26+; won't run on the 2019 Intel dev machine.
- **stdio child** (chose a socket for reconnect/robustness) and **JVM-spawned child / always-on login
  agent** (chose on-demand socket activation as the idiomatic, resource-light pattern).

**Consequences.** The repo gains its **first macOS-native build artifact** (a Swift target built/signed
on macOS — the x86_64 slice is verified on the Intel Mac; the arm64 slice is cross-built but unverified
until an Apple Silicon machine). The Mac becomes a **build/sign/verify step, not the dev environment**:
~80–90% of the work — the IPC contract, the JVM socket client, `SidecarSpeechToText`, the permission
state machine + capability ports, the in-app permission UX, and the Conveyor packaging config — is
**Linux-authorable and Linux-verifiable** against the stub helper; only the Swift source's runtime
behavior and TCC prompts need the Mac. The shared `DictationStatus.Permission*` states and
`onOpenOsAppSettings` (Android-only today) now **activate on macOS**. Packaging grows a LaunchAgent
install/registration + uninstall path, and a second signed inner binary to notarize.

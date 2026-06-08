# On-device speech-to-text: a portable whisper baseline with opportunistic native fast paths

**Context.** The client is gaining speech-to-text. Its first and only v1 use is **[[Dictation]]** —
the mic affordance on the New create surface fills the title/notes fields (ADR-0015/0016), streaming
on-device speech into an editable **[[Transcript]]**. Two non-goals are explicit: **voice commands**
routed to the command registry (ADR-0007), and the **[[Brain dump]]** structured extractor (Stage 2,
deferred). The client must span **Android, iOS, macOS, Linux, Windows**, and no single system STT
stack covers all five: Apple `SpeechTranscriber` is iOS/macOS 26+, Android ML Kit speech is
alpha/device-gated, and the generic Android `SpeechRecognizer` may stream to remote servers and is
documented as "not for continuous recognition". Privacy is a top priority (ADR-0009): recognition
must **never silently fall back to cloud**.

**Decision.**

- **Portable baseline = whisper.cpp**, the canonical portability layer across all five targets.
  Native fast paths (Apple `SpeechTranscriber`, Android ML Kit / on-device `SpeechRecognizer`) are
  added opportunistically **later** and are purely **additive** — v1 ships **whisper-only**.
- **The shared seam sits at the [[Transcript]] altitude, not audio.** `commonMain` `SpeechToText`
  exposes `availability()` + a streaming `listen(locale, continuityHint): Flow<TranscriptEvent>`
  (Partial/Final/Error). Each engine owns its **own mic capture + VAD internally**; there is
  deliberately **no shared audio/PCM abstraction** — the native fast paths are vertically integrated
  (own mic + recognition, won't surrender raw audio), and only whisper consumes PCM and owns VAD.
  `continuityHint` lets the future long-form **[[Brain dump]]** rank whisper above short-utterance
  native recognizers.
- **Module `core/speech`** (`deferno.kmp.library` + `deferno.di`), mirroring `core/secure`: a
  `commonMain` seam + `UnavailableSpeechToText` fallback, per-platform engine impls, and
  `SpeechBindings.<platform>.kt`. Bound at **AppScope** — speech is a **device capability,
  identity-independent**, not AccountScope (ADR-0014). Mic UI lives in `app/shell` + the app layer
  (`NewComponent` gains dictation state; the New screen renders the mic), **never in core** (core is
  Compose-free, ADR-0004).
- **Per-target idiomatic native interop** — the repo's **first native code**. **Android:** vendored
  whisper.cpp git submodule + CMake/NDK + a thin JNI bridge, `AudioRecord` capture. **iOS:** cinterop
  against the official `whisper.xcframework`, `AVAudioEngine` capture (framework link is macOS-only,
  consistent with ADR-0006). **JVM desktop:** the maintained `whisper-jni` Maven artifact,
  `TargetDataLine` capture. The asymmetry is deliberate — **build-from-pinned-source on mobile** (no
  trustworthy prebuilt on the privacy-critical path), a **managed artifact on desktop** (lowest-priority
  target, the `java.keyring`-shaped precedent). Manage whisper version skew by pinning the submodule and
  `whisper-jni` to the **same upstream tag** in `libs.versions.toml`.
- **Capability-tiered selection with structural never-cloud.** A `SpeechToTextSelector` picks, per
  `listen()`, the highest-ranked engine whose `availability()` reports **Available now**; **whisper is
  the always-available floor**; a native fast path outranks it only when `availability()` confirms
  genuine on-device readiness. "Never cloud" is **structural**: **no cloud engine is ever registered in
  the DI graph** — native engines are forced on-device (`requiresOnDeviceRecognition` / the on-device
  variant) or report unavailable, so no code path can select a server recognizer; a
  chosen-but-unavailable engine falls to the whisper floor.
- **Engine choice is a device-local [[App setting]]** (multiplatform-settings — a new dependency, owned
  by `core/speech`), default **Whisper** until better/more-specific engines exist; the user may
  switch/opt-in via the Settings [[Destination]]. **Not a synced [[User setting]]** (availability is
  per-device, identity-independent).
- **[[Dictation]] is enabled by default, OS-permission-gated** (first mic tap prompts; gentle "needs
  microphone access" on deny; deep-link to OS settings on permanent denial). Audio is **transient**:
  PCM in memory, **never written to disk, persisted, uploaded, or logged** — extending ADR-0009's
  no-third-party-analytics / never-log-PII stance to **never logging audio or Transcripts**. Only the
  user-edited Transcript text survives.
- **English-only v1** (`small.en`, ADR-0019). Non-English device locales report **unavailable** rather
  than silently mis-transcribe; a multilingual model is deferred.
- **Testing (ADR-0006):** the `SpeechToTextSelector`, the `SpeechToText` seam, fakes, and the
  `SpeechEnginePreference` contract are **measured** by the headless merged-core gate; the native
  `WhisperSpeechToText` impls, `SpeechBindings.*`, and the multiplatform-settings-backed preference impl
  are **excluded** via `CoverageConfig` globs (same rationale as the secure/network/database platform
  actuals). **No automated native-correctness CI in v1** — validated manually now, then by
  `connectedAndroidTest` + macOS native tests once the real-hardware runner lands.

**Amendment (2026-06-08, #94 — desktop divergence).** The original decision pins the submodule and
`whisper-jni` to the **same** upstream tag. In practice the maintained desktop artifact lags: the mobile
submodule is at whisper.cpp **v1.8.6**, but `whisper-jni` tracks whisper.cpp 1:1 and its newest published
release is **1.7.1** (whisper.cpp **v1.7.1**) — there is **no** build of v1.8.6. So the desktop engine
**intentionally diverges one minor version from mobile**: `whisper-jni` is pinned to its latest maintained
release rather than forced to mobile's tag, and both targets stay pinned to a *definite* tag for
reproducibility (the principle holds; "same tag" does not, when no aligned artifact exists). The skew is
benign — the seam sits at the [[Transcript]] altitude, and the `small.en` q5 ggml weights are format-stable
across whisper.cpp 1.7↔1.8 — so behaviour is unaffected. Revisit if `whisper-jni` ships a v1.8.x build.

**Forward path.** Native fast paths (ML Kit, `SpeechTranscriber`) land additively through the selector.
The **[[Brain dump]]** extractor (Stage 2) consumes the Transcript and will relax ADR-0015's "never
inferred". A multilingual model follows. The whisper baseline rolls out **Android → desktop → iOS**
(Android-first, ADR-0003).

**Consequences.** The repo gains its **first native toolchain** (NDK/CMake; macOS to link iOS) and a
**whisper version skew** to coordinate. A Transcript dictated offline **still can't create a Task
offline** — create is online-only (ADR-0016); dictation only fills text, the create still gates on
connectivity. The Settings Destination now **mixes synced [[User setting]]s with one device-local
[[App setting]]**.

**Rejected.**

- **An audio/PCM-level seam** — breaks the vertically-integrated native engines; leaky the moment a
  fast path is added.
- **Cloud/server STT of any kind** — privacy (ADR-0009).
- **One forced interop mechanism everywhere** — a hand-rolled JVM JNI bridge for three desktop OSes
  isn't worth it over `whisper-jni`.
- **ONNX Runtime as the STT baseline** — whisper.cpp chosen as the portability layer per the research.
- **Making engine choice a synced [[User setting]]** — availability is per-device; it would push "use
  System engine" onto a device that lacks it.

# Whisper model distribution: platform asset delivery, not bundled-in-binary, not self-hosted

**Context.** The on-device Whisper baseline (ADR-0018) needs **model weights present on the device**
before recognition can run — the `SpeechToText` engine can't report Available without them. The v1
weights, **small.en quantized (q5), roughly 182 MB**, are far too large for a base APK: bundling them
in the binary **bloats every install** — including the majority of users who never use Dictation —
and risks tripping store size limits. The team also wants to **avoid standing up its own hosting** for
a large static asset on the privacy-critical recognition path.

**Decision.**

- **small.en (quantized, q5) is the v1 baseline model.** Chosen over **tiny.en** — too much Whisper
  hallucination for a task app, where a Dictation's Transcript becomes a real Task and a fabricated
  word becomes a fabricated commitment — and over **base.en** (smaller but less accurate).
  **English-only** (ADR-0018).
- **Delivery rides the platform store's asset mechanism — not the base binary, not self-hosted.**
  - **Android:** **Play Asset Delivery as an install-time pack** — present from first launch, **off
    the base-APK size budget**, Play-hosted.
  - **iOS:** **On-Demand Resources** (App Store-hosted), **prefetched** so the weights are in place
    before first Dictation.
  - **Desktop:** **bundled in the installer** — `WhisperSpeechToText` (via whisper-jni) loads from
    the packaged path; desktop installers are large anyway, so there's no per-install bloat argument.
- **The model download is not a privacy violation, stated explicitly.** The **"audio never leaves the
  device" invariant** (ADR-0018, ADR-0009) governs **recognition** — audio → Transcript — which stays
  fully on-device. **Fetching static model weights once sends no audio anywhere.** Recorded here so a
  future change can't "fix" a misread of the invariant by bundling on a false privacy pretext.

**Forward path.** Upgrading small.en to a **higher-accuracy or multilingual model** becomes a future
**model-quality App setting** (device-local, never synced, never crossing Accounts — parallel to the
speech-engine choice of ADR-0018). Larger and multilingual weights **ride the same asset-delivery
rails**; no new distribution mechanism is needed to grow the model.

**Consequences.** Each platform gains **store-specific build plumbing** — an **asset-pack module on
Android**, **ODR tags on iOS** — which is more than a `libs.*` catalog line. **iOS ODR may briefly
show "preparing speech…" on first use** if prefetch hasn't completed in time; Android's install-time
delivery avoids even that transient state. The Whisper engine **cannot report Available until a model
is present**.

**Considered & rejected.**

- **Bundling the model in the base APK/IPA** — bloats every install (including non-dictating users)
  and trips store size limits.
- **Self-hosting the weights on our own CDN** — the exact hosting burden we set out to avoid, and it
  adds a **day-one availability dependency on the privacy-critical path** (no model fetch, no
  Dictation).
- **Shipping only tiny.en to keep the app small** — hallucination too high for a task app whose
  Transcript becomes a Task.

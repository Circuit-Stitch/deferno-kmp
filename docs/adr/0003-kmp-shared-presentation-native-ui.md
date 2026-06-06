# KMP shared core through presentation; native UI per platform

**Context.** Native clients on Android, iOS, and desktop, authored by one team. Goals: write-and-
test logic once, strong modularity, and behavioral consistency across platforms.

**Decision.** A shared **Kotlin Multiplatform** core holds models, networking (**Ktor** +
kotlinx.serialization), persistence (**SQLDelight**), repositories, the outbox/sync engine, auth +
secure-storage ports, the envelope-versioning shim, **and presentation + navigation** —
ViewModels/state-holders and the navigation tree via **Decompose**. Only the **View** is native
(Jetpack Compose on Android, SwiftUI on iOS, Compose Desktop on desktop). **SKIE** bridges
`Flow`/suspend into idiomatic Swift. DI is **kotlin-inject + kotlin-inject-anvil** (compile-time,
KMP-native, Hilt-like contribution ergonomics).

**Considered & rejected.**
- *Compose Multiplatform shared UI* — rejected: we want genuinely native UI per platform.
- *Option A — share only data/domain, native presentation per platform* — rejected: presentation
  logic written and tested twice; platforms can drift.
- *B-lite — shared ViewModels but native navigation* — rejected in favor of centralized navigation
  and deep-link routing, and a fully agent-drivable shared state model.

**Consequences.** iOS consumes Kotlin state via SKIE (not hand-written Swift observables).
**Decompose is the one common-code lock-in** — the single genuinely hard-to-reverse piece; the
share line is otherwise a *per-feature, reversible seam* (retreating to native presentation re-homes
one feature at a time and leaves the data/domain core untouched). Platform hardware (mic/STT,
camera, biometrics, notifications) is exposed to common code via per-platform capability ports
(`expect`/`actual` or DI), the same boundary used for the secure-storage token vault.

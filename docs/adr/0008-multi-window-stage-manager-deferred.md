# Multi-window / Stage Manager: deferred past v1, but not precluded

**Context.** iPad **Stage Manager** (and Android multi-instance / desktop multi-window) is wanted but
out of v1 scope. The risk is that single-window assumptions baked into v1 turn it into a rewrite
later. This ADR records the deferral *and* the guardrails that keep it a localized addition.

**Decision.** v1 ships single-window, but the architecture must hold these guardrails:

- **(G1) Layout off continuous window metrics only** — size classes / available width, **never**
  device-type checks (no `isIPad`). Any width must lay out correctly even if only standard sizes are
  tested in v1.
- **(G2) Multi-scene-ready from day one** — SwiftUI `WindowGroup` + iOS multi-scene config; **each
  window/scene gets its own Decompose component-tree root**; the **data layer** (repositories,
  SQLDelight, sync engine, `AccountManager`, secure vault) are **process-global singletons shared
  across scenes**, while **presentation is scene-scoped**. The DI scopes are structured this way now.
  *(Refined by ADR-0014: the cross-Account infrastructure here — `AccountManager`, the secure vault, the
  network client — stays process-global, but **repositories, SQLDelight, and the sync engine are
  per-Account `AccountScope`**, torn down and rebuilt on Active-Account switch, to honour the ADR-0002
  isolation boundary.)*
- **(G3) Account context is scene-scoped, not a hard global** — a future window can show a different
  [[Active Account]]. v1 behaves as one Active Account, but the seam exists.
- **(G4) Per-scene lifecycle** (Decompose/Essenty per tree) — no single-foreground assumption; support
  external-display scenes.
- **(G5) All UI state retained in shared components** (Views are pure renderers), so arbitrary resize
  never drops state.

**Consequences.** These same guardrails deliver **Android multi-instance and desktop multi-window for
free**, so they aren't iPad-specific tax. The v1 cost is low — DI scoping discipline, avoiding
device-type checks, and using `WindowGroup`. Stage Manager *hardening* (arbitrary-resize edge cases,
external display) is a later milestone, not a re-architecture.

**Rejected.** Single-Activity/single-Scene + a global "current account" (cheaper in v1, a rewrite
later).

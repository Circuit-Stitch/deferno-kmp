# Android draws edge-to-edge; each component owns its window insets

**Context.** The Android app draws **edge-to-edge** — `MainActivity` calls `enableEdgeToEdge` and makes
the system bars transparent, so app content extends under the status bar and the navigation bar. This is
not a stylistic choice we can revisit: on **Android 15+ (API 35+) a target-SDK 35+ app is edge-to-edge by
OS fiat** — `Window.setDecorFitsSystemWindows(true)` and the related opt-out flags are deprecated and
ignored — and this app targets SDK 36 (`ProjectConfig`). `enableEdgeToEdge` on older devices just makes
today's behaviour match that future, so inset bugs surface in development rather than in the wild.

The shell's top bar already honours this (`ShellChrome` pads `WindowInsets.statusBars`, ADR-0031). But the
rule had never been written down, so two surfaces got it wrong: the modal-move **`MoveModeBar`** (a *pinned*
bottom bar with the **Done** affordance, ADR-0034 decision 6) drew **behind** the system navigation bar —
on a 3-button-nav device it was wholly off-screen, so a user who entered move mode could not leave it — and
the Tasks tree's `LazyColumn` clipped its last row under the nav bar (no way to scroll it clear). Compose
screenshot tests render no system bars, so neither was caught by the gate.

This codifies the official Android / Material 3 guidance ("Display content edge-to-edge in Compose") so the
next pinned bar or scrolling pane gets it right by default. **Android-target only**: desktop window insets
are empty (the convenience modifiers no-op there) and the Apple SwiftUI Views own their own safe areas
(ADR-0003/0028), so this is a property of the Android [[View]] layer, not the shared component state.

**Decision.** Three principles, lifted from Google's edge-to-edge guidance:

1. **Embrace edge-to-edge; never opt out.** Keep `enableEdgeToEdge` and transparent system bars. "Turn off
   edge-to-edge" is not an option (deprecated + ignored on API 35+) and the top bar already depends on the
   setup. Content drawing under the bars is the desired posture, not a bug.

2. **Handle insets at the component that owns them — not once at the root.** A single root-level inset pad
   is the anti-pattern Google calls out: it defeats edge-to-edge by stopping *all* content at the bars.
   Instead, **scrolling content draws under the bars** and applies the inset as **`contentPadding`** (so the
   last row scrolls clear of the nav bar), while **fixed chrome pads itself**. Use `WindowInsets.*` +
   `Modifier.windowInsetsPadding(...)` (the manual form the top bar already uses) per surface; consume an
   inset where it is applied so a nested surface can't double-count it.

3. **A pinned bar consumes the bottom (+ horizontal) system-bar inset itself.** This mirrors Material 3:
   `Scaffold`'s `bottomBar` slot and `BottomAppBar` apply `WindowInsets.systemBars.only(Horizontal + Bottom)`
   automatically. A bespoke pinned bar (the `MoveModeBar`) does the same — `systemBars`, not just
   `navigationBars`, so it is also correct in landscape and under a display cutout. The bar's background still
   fills edge-to-edge behind the bar; only its *content* is inset.

**Consequences.** The `MoveModeBar` content pads `WindowInsets.systemBars.only(Horizontal + Bottom)` —
Done + ↑↓‹› clear the nav bar. The Tasks `LazyColumn` adds the bottom system-bar inset as `contentPadding`
**only when no move bar is present** (in move mode the bar owns that inset, so the list above it must not
double-pad). New pinned bottom bars copy the bar; new scrolling panes copy the list. The convention is the
manual per-component `windowInsetsPadding` already established for the top bar (ADR-0031) — we deliberately
do **not** introduce a `Scaffold` per pane just for insets. Because the JVM-fast screenshot gate (ADR-0006)
renders no system bars, inset correctness is **verified on device / manually**, not by the gate.

**Rejected.**

- **A global root-level inset pad** (e.g. `safeDrawingPadding()` on the shell body) — the simplest fix, but
  the exact anti-pattern Google warns against: it makes every list stop at the bars instead of scrolling
  under them, throwing away edge-to-edge for the whole app to patch one bar.
- **Disabling edge-to-edge** (`setDecorFitsSystemWindows(true)`) — deprecated and a no-op on API 35+, and
  the top bar's `statusBars` handling assumes edge-to-edge; turning it off would look correct on Android 13
  and break on Android 15.
- **Padding only `navigationBars`** on the bar — fine in portrait, wrong in landscape / with a cutout;
  `systemBars.only(Horizontal + Bottom)` is what Material 3's own bottom bars use.

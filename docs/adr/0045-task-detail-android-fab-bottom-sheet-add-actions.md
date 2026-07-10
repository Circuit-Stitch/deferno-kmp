# Task detail add-actions: an Android FAB + ModalBottomSheet, not the expressive FAB menu

**Status.** Accepted. An **Android-only** refinement of the ADR-0044 [[Task]] detail; ADR-0003/0004
(genuinely native UI per platform) is why it is Android-only — the Compose Desktop app, iOS, and macOS
keep their own affordances. Records **why the native `FloatingActionButtonMenu` speed-dial was
rejected** in favour of a stock FAB + bottom sheet, so the decision isn't silently re-litigated when
someone spots the expressive component in the docs.

**Context.** The ADR-0044 task detail exposes three "add" actions — **Add subtask**, **Add comment**,
**Add to today's plan** — through omni-present inline affordances: an inline *Add to today's plan*
button in the Info tab and *Add subtask* on the shell's contextual kebab. The design intent (and the
original mockup) is a single **fan-out** trigger. On **Android** — which is also how the app runs on a
Chromebook, where a [[FloatingActionButton]] is the idiomatic primary affordance — those scattered
inline entry points should collapse into **one FAB**. Desktop, iOS, and macOS are out of scope here and
must be **byte-for-byte unchanged**.

The obvious match for the fan-out mockup is Material 3's expressive **speed-dial**
(`FloatingActionButtonMenu` + `ToggleFloatingActionButton` + `FloatingActionButtonMenuItem`), which
would give focus management, a scrim, and the expand/collapse a11y announcement **for free**. It cannot
be used, for a chain of version-boundary reasons that is the substance of this ADR.

**Decision.** On **Android only**, move the three add-actions off the inline affordances and behind a
**stock `androidx.compose.material3.FloatingActionButton`** that opens a Material 3
**`ModalBottomSheet`** listing them (*Add subtask* · *Add comment* · *Add to today's plan*). Both
components are **stable Material 3 already on the classpath** — no new dependency, no runtime bump. The
sheet is **text-only rows** (no leading icons), consistent with the existing `StatusPickerSheet` the
STATUS row already uses. The FAB lives only in the `androidMain` `TaskDetailScreen` wrapper; the shared
`TaskDetailContent`/`TaskBody` body stays platform-neutral.

**Rejected — the native `FloatingActionButtonMenu` expressive speed-dial.** It would match the mockup
and hand us focus/scrim/announcement a11y for free, but it isn't reachable from where the body lives.
The reasoning chain, precisely:

- **The shared body is JetBrains-CMP `material3`, not androidx.** `TaskDetailContent`/`TaskBody` are
  `commonMain` in a Compose-Multiplatform module built against JetBrains
  `org.jetbrains.compose.material3:material3:1.9.0` (the `composeMaterial3` catalog version), not the
  androidx artifact directly.
- **On Android that JetBrains 1.9.0 delegates down to androidx `material3:1.4.0`.** Its Gradle module
  metadata (verified in the `.module` file) resolves the Android variant to Google
  `androidx.compose.material3:material3:1.4.0`; the `app/androidApp` Compose BOM `2026.05.01`
  **independently** resolves the *same* `1.4.0`. So Android effectively runs androidx `material3` 1.4.0.
- **The speed-dial APIs are not in `material3` 1.4.0.** `FloatingActionButtonMenu`,
  `ToggleFloatingActionButton`, and `FloatingActionButtonMenuItem` are absent from 1.4.0 (verified
  against the actual `classes.jar`: it carries only the plain `FloatingActionButton` plus an *internal*
  `FabMenuBaselineTokens` token class). They first ship **publicly on the 1.5.0-alpha line**, gated
  `@ExperimentalMaterial3ExpressiveApi`.
- **Pulling 1.5.0-alpha forces the whole Android Compose runtime forward.** The latest
  `androidx.compose.material3:material3:1.5.0-alpha23` POM transitively **forces the entire Android
  Compose runtime to `1.12.0-alpha03`** — `foundation`, `foundation-layout`, `ui`, `ui-text`,
  `runtime`, `material-ripple`. That overrides the stable Compose BOM **and sits ahead of the androidx
  runtime the JetBrains Compose-Multiplatform 1.11.1 shared code was compiled against** — a
  cross-boundary Compose-runtime version mismatch, exactly the `IncompatibleComposeRuntimeVersionException`
  failure class CLAUDE.md warns about. A disproportionate, **app-wide destabilization risk for one FAB
  menu**.
- **Secondary: it mandates per-item icons.** `FloatingActionButtonMenuItem` requires an `icon` per row;
  `core/designsystem` deliberately carries **no material-icons dependency**, and adding a heavy icons
  artifact (or hand-drawing vectors) purely to feed this menu was declined.

The chosen stock `FloatingActionButton` + `ModalBottomSheet` sidesteps every link in that chain: both
are **stable Material 3 already resolved on the classpath** (no version force, no runtime skew), the
sheet gives **focus-trapping, scrim, tap/drag dismissal, and sheet semantics for free** (a11y-first,
the property that made the speed-dial attractive), and its **text-only rows need no icon sourcing**.

**Consequences.**

- **The shared body gains an `externalAddActions` flag (default `false`).** Default `false` keeps
  **desktop unchanged**; the `androidMain` wrapper passes `true`, which **hides the inline *Add to
  today's plan* button and the kebab's *Add subtask* item** so those actions live *only* on the sheet —
  no double affordance on Android.
- **A new `onAddCommentRequested()` component seam + a `revealCommentComposer` monotonic token** let
  the sheet's *Add comment* row **jump to the Comments tab and focus the composer**, mirroring the
  add-subtask reveal token established in ADR-0044 (a bumped counter the View observes to trigger a
  one-shot tab-switch + focus).
- **The FAB is `androidMain`-only.** It lives solely in the `TaskDetailScreen` Android wrapper, so
  `app/desktopApp`, iOS, and macOS render exactly as before — desktop keeps its inline *Add to today's
  plan* button; iOS/macOS keep their own native SwiftUI affordances (out of scope here per
  ADR-0003/0004).
- **Revisit the native speed-dial** if/when JetBrains CMP `material3` ships `FloatingActionButtonMenu`
  on a version **aligned with the project's Compose runtime** — at that point the a11y-for-free win
  returns with none of the version-force risk, and the sheet can be swapped behind the same FAB.

Cross-references ADR-0044 (the tabbed Task-detail body + reveal-token pattern this reuses) and
ADR-0003/0004 (native UI per platform, the reason a FAB is Android-only).

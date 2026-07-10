# Task detail: connected-parent header, tabbed sections, read-only journey status

**Context.** The [[Task]] detail is one shared body ([[View]]-neutral `TaskDetailContent`, rendered by
the Android/desktop Compose screens and the iOS/macOS SwiftUI `TaskDetailView`). It had grown **three
stacked heading layers**: the shell [[Chrome]] (a search pill on the Tasks [[Destination]], a drilled
`← + title` bar on the Plan drill), a generic `PaneHeader("Details")` baked unconditionally into
`TaskDetailContent`, and the `#231` body title block (kind chip + title + kebab). On the Plan/desktop
drill the **title drew twice and Back drew twice**; on the compact Tasks detail the shell never went
`drilled` (ADR-0031's one-chrome model doesn't reach Tasks, the multi-pane carve-out), so a **search
pill sat above a foreground detail** with only the `PaneHeader`'s floating "Back" to escape. Below the
headers the screen was a single long scroll mixing Properties, Subtasks, Attachments, and Comments, and
[[Working state]] was edited through five always-visible inline chips.

**Decision.** Rebuild the task detail as a **self-contained screen with one heading**:

- **A connected-parent header is the single heading.** The immediate parent (`Task.parentId` only —
  not the full ancestor spine) renders as a muted node **drawn thread-connected** above the current
  item (kind chip + title + `ref`), and is **tappable → pushes the parent's detail** (back returns).
  No parent → the item stands alone. The redundant `PaneHeader("Details")` is **deleted on every
  platform**; downward children stay the Subtasks list (below), not part of this header.
- **The body is three tabs — `Info` · `Comments` · `History`** (Info default). Info holds
  description (NOTES) → an **Add to today's plan** button → the properties table → Subtasks →
  Attachments. Comments is the per-item comment feed; History is the per-item item history (ADR-0043).
  This matches CONTEXT.md's per-item comments vs item-history vs global Activity split.
- **STATUS is a read-only "journey" indicator** — three slots, **initial `TO-DO` → present →
  terminal `DONE`** — rendering a **display-only journey vocabulary**, a *reading* over `WorkingState`
  plus the orthogonal server-derived [[Blocked / blocker]] flag: `TO-DO`(Open) · `IN-PROGRESS` ·
  `IN-REVIEW` · `DONE` · **`NOT DOING`**(Dropped) · **`BLOCKED`**. `blocked` overrides the middle
  label; `Dropped` reads as a **middle marker with a dashed tail to a struck-through `DONE`** (shelved,
  not headed to done). It is **info-only**: state changes move to a **status picker sheet** opened by
  **tapping the STATUS row**; the inline working-state chips are removed. `WHEN` (`DUE ON` =
  `completeBy` + time, shown with a relative "N days away") and `LABELS` stay **inline-editable**.
  The mockup's second date **`DO BY` is a future concept — omitted** until the model carries it.
- **The shell top bar for a drilled task detail is back + a contextual overflow, no title** (Add
  subtask · Break this down · Delete). The connected node is the heading, so the bar carries no title.
  This **keeps ADR-0031's one shell-computed chrome**: `ChromeSpec` gains a **contextual overflow** for
  a drilled surface, and the **compact Tasks detail now computes `drilledChrome` (empty title)** instead
  of the search pill — closing the "shell doesn't know a Tasks detail is open" gap.

**Consequences.** One heading instead of three; the search-pill-over-detail, the duplicate title, and
the floating "Back" are gone. Because the body is shared, the Compose fix lands **once for Android +
desktop**; the iOS/macOS SwiftUI `TaskDetailView` (which already carried a `showsHeader` escape valve)
is **ported to the same shape**. `blocked` surfaces purely as a **reading** (server truth, orthogonal
to `WorkingState`). New shared seams: a journey-status reading (`WorkingState` + `blocked` → slot +
label), an immediate-parent summary on `TaskDetailState`, and a relative-day date format. **Test cost**:
the one `PaneHeader` "Back" interaction test repoints to the new close affordance, ~8 Roborazzi goldens
re-record, and coverage is **added** for the drilled Tasks chrome + the journey-status states (both
currently gaps). `ChromeSpec` growing a contextual overflow is a small amendment to ADR-0031/0032's
otherwise-fixed action catalog.

**Rejected.**

- **Keep the `PaneHeader`, relabel it to the task title** — still stacks a third bar under the drilled
  shell bar and the body block, and does nothing for the search pill or the single-heading goal.
- **Toolbar-as-heading** (put the title in the bar, drop the body title block) — reverses `#231`'s
  "the prominent title lives in the body", and gives no room for the connected-parent context.
- **Detail owns its own toolbar; suppress the shell bar for this surface** — partially undoes
  ADR-0031's "one shell chrome, no per-screen headers"; extending `ChromeSpec` with a contextual
  overflow keeps that invariant intact.
- **Full ancestor spine in the header** — recursive parent fetches and a variable-height chain; the
  immediate parent is the high-value context and a later spine is purely additive.
- **A `Blocked` `WorkingState`** — `blocked` is a server-derived edge orthogonal to the lifecycle, not
  a sixth state (CONTEXT.md); the indicator shows it as a reading, never as a stored status.

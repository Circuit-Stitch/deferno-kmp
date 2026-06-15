# One shell-computed `ChromeSpec` top bar

**Context.** ADR-0003 splits each surface into **shared, Compose-free component state** + a **thin,
per-platform [[View]]**, and ADR-0013/0017 put a shared shell above the [[Destination]] graph. But the
**top bar was drawn twice**: each screen rendered its own header (`PaneHeader`, `DetailHeader`, bespoke
inline bars) *and* the shell drew a static top bar (menu · brain dump · new) above them. Two bars,
inconsistent actions — the header buttons "came and went" per page because each screen decided its own,
while the shell's row stayed fixed. There was no single place that answered "what does the top bar show
for the foreground surface?".

**Decision.** Introduce a Compose-free **`ChromeSpec(title, drilled, actions)`** in `app/shell`
`commonMain`, computed reactively in `MainShellComponent` (`chrome: StateFlow<ChromeSpec>`, built by
`chromeFor(active)` / `rootChrome(...)`) from the active Destination + its tier-3 drill-down (ADR-0030).
One adaptive top bar (`ShellChrome`) renders it: **menu ☰ + title + create actions** (Brain dump, New,
per-screen Refresh) at a Destination root; a **back arrow ← + the detail's own title** (the Task name,
the Settings category) when `drilled`. The per-screen headers on the single-pane Destinations — Plan,
Settings (list + category), Profile — are **deleted** on Android and desktop. The Calendar New-with-date
pre-fill (#74) moves into the chrome computation. Actions are a fixed catalog (`ChromeActionKind`:
Refresh · BrainDump · New); each platform View maps a kind to its glyph + a11y label.

The chrome `title` is deliberately **shell presentation state**, not a domain label and not the nav-suite
label — a Destination's nav row reads "Plan" while its screen title reads "Today". This **bends** the
convention that display labels are a View concern: a title that one shared bar renders on three
platforms must live with the chrome it drives, so iOS reads **one string** rather than re-deriving it in
SwiftUI. Titles that are genuinely a screen's own (a Task name) flow up through the tier-3 state the
chrome already reads.

**Consequences.** One bar, computed once, rendered natively three ways (Compose on Android/desktop via
`ShellChrome`, SwiftUI on iOS from the same `ChromeSpec` — ADR-0017/0029). The header buttons no longer
appear and disappear arbitrarily; Back and Refresh now live in the shell bar, **outside** the rendered
screen (two isolated-screen interaction tests were updated to drive the component directly, and the
`shell_chrome_closed` Roborazzi golden regenerated). **Tasks is the multi-pane carve-out** (ADR-0007
tier-2): its co-resident [[Pane]]s keep their own headers, so the shell bar shows only the global actions
there (`chrome.title = ""`). Overlays (Search/New/Feedback/Brain dump) stay **modal surfaces above the
chrome** with their own headers — they are not driven by `ChromeSpec`. The iOS SwiftUI render of the new
chrome model is tracked for the Mac (#216).

**Rejected.**

- **Keep per-screen headers** — the status quo this ADR removes; it gave two stacked bars and per-page
  action drift, and forced every platform's screens to re-decide chrome that is really shell state.
- **A Compose chrome component shared from `core/designsystem`** — `app/shell` must stay Compose-free to
  keep its iOS target (ADR-0017 #27), and iOS renders the bar in SwiftUI. The *spec* is the shared
  artifact; the bar is per-platform, exactly as ADR-0003 prescribes.
- **Title as a pure View concern (each platform derives its own)** — works for Android/desktop but makes
  iOS re-implement the title logic in Swift; centralising it in `ChromeSpec` keeps the three surfaces
  word-for-word identical with no duplicated derivation.

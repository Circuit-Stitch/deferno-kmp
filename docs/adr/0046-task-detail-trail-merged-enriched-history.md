# Task detail: one reverse-chronological Trail (comments + enriched read-only history), not two tabs

**Status.** Accepted. **Supersedes ADR-0044's `Comments`/`History` tab split** (its second bullet) and
realigns the client with ADR-0043 + CONTEXT.md, which described the two per-item feeds as **one
interleaved feed** all along. Android + Compose Desktop land this pass (the shared body); the iOS/macOS
SwiftUI `TaskDetailView` is ported as a **follow-up** (same split ADR-0044 used).

**Context.** ADR-0044 rebuilt the [[Task]] detail as three tabs — `Info · Comments · History` —
justified as matching "CONTEXT.md's per-item comments vs item-history vs global Activity split." But that
glossary section disambiguates three **stores** (the [[Comment thread]], [[Item history]], and the global
Activity ledger), not a **UI presentation**; CONTEXT.md's own *Comment thread* entry says the two per-item
feeds are *"interleaved chronologically with item history into one feed."* The merged seam already
exists: `DefaultTaskDetailComponent` builds one `activity: List<ActivityItem>` (`sealed { Comment;
HistoryEvent }`) via `mergeActivity(...)` sorted by instant — ADR-0044 merely `filterIsInstance`'d it into
two tabs (`TaskDetailSections.kt`: *"the ADR-0043 feed, split by ADR-0044"*). So the tab split *drifted*
from the documented model; re-merging is nearly free at the data layer.

Meanwhile the history half is near-useless. `ItemHistoryEvent.label()` collapses **every** event to one of
10 fixed strings and **discards the whole payload** — a verb + a date, nothing else. On a real project
task (`#7 "deferno improvements"`, pulled live), **30 of 36 rows are a title-less "Split off a task"**; a
single edit session stacks as N "Edited this task" rows; `pinned` toggles and same-instant
`Created`+`ParentAssigned` pairs add pure noise. Yet the wire *carries* the signal the render throws away:
`Updated.fields` (which fields changed), `StatusChanged.from/to` (the exact transition), and peer ids
(`Split.child_id`, `Moved.*_parent_id`, `ParentAssigned.parent_id`, `FoldedInto.next_task_id`,
`MergedChild.child_id`) that resolve to titles from the **local item cache** the component already holds.

**Decision.**

- **Collapse the body to two tabs — `Info · Trail`.** The **Trail** is the existing merged `activity`
  list, re-sorted **reverse-chronological (newest first)** (`sortedByDescending { it.at }`) and rendered as
  one interleaved feed. The comment **composer stays inline at the top** (non-sticky); the ADR-0045 FAB
  *Add comment* row keeps working via `revealCommentComposer` — now scroll-to + focus, not tab-switch.
  Each row carries a **leading unicode glyph** by kind (💬 comment · ↻ status · ✂ split · ✎ edit · ●
  created · …) — **no material-icons dependency** (ADR-0045). The tab is named **"Trail"**, not "Activity",
  to end the word's overload with the global **Activity** Destination (the very ambiguity CONTEXT.md fights).
- **Enrich the history rows to the data limit — by rendering the payload, NOT by coalescing.** Every row is
  kept (the "flood" is accepted); no grouping, filtering, or de-duping is built.
  - `StatusChanged` → **"Status: <from> → <to>"** in the **editor vocabulary** (`Open` · `In progress` ·
    `In review` · `Done` · **`Set aside`**), **never** the `TO-DO`/`NOT DOING` journey labels — those are
    forbidden outside the status indicator ([[Working state]], CONTEXT.md). An `Unknown` side → the generic
    "Changed the status".
  - `Split` / `Moved` / `ParentAssigned` / `FoldedInto` / `MergedChild` → resolve the **peer title** from
    the local cache and render it (`Split off: "…"`, `Moved under: "…"`, `Parent set: "…"`, …), degrading to
    **"another item"** when the peer is aged out of the window (the ADR-0043 contract). **Render never
    fetches.**
  - `Updated` → name the changed **field(s)**, humanized (`Edited: Deadline, Labels`); an unrecognized token
    → the generic "Edited this task" (never leak raw `snake_case`). **No old→new value** — the wire carries
    field *names* only; that limit is inherent, not a rendering gap.
- **Resolution lifts into `commonMain` `feature/tasks`, not the Compose `label()`.** The component resolves
  peer titles + `from`/`to` onto typed data on `ActivityItem.HistoryEvent`/`TaskDetailState` (peer via
  `ItemRepository.observeItems()` — any-[[Item kind]], since the tree spans kinds — a one-line constructor
  add wired at its three build sites). Each [[View]] maps that typed data to its own localized resource
  (the "typed code, View maps to resource" convention), so **all four platforms share the logic** and only
  the string interpolation is per-View.

**Consequences.** The tab split is gone; the merged feed doc and product realign. New/changed work: the
merge sort flips; `ItemRepository` is injected into `DefaultTaskDetailComponent` (+3 build sites, one line
each); new **parameterized strings** (status transition, the five peer verbs, the field labels, "another
item") land in **all 5 Compose locales + the Apple `Localizable.xcstrings`** now — or `L10nCatalogParityTest`
fails — even though **SwiftUI won't render them until the follow-up**; existing labels are reused where they
exist (the status-picker state labels, the Info-tab property labels). The `DetailTab.Comments`/`History`
split drops to a single `Trail`; ~the ADR-0044 Roborazzi goldens re-record; the enrichment mapping is
TDD'd in `commonTest`. No wire, DTO, or DI-graph (KSP) change — the component is hand-constructed.
**Follow-up:** the iOS + macOS SwiftUI `HistoryRow` + both Kotlin bridges consume the shared typed data.

**Rejected.**

- **Keep three tabs, only enrich History.** Preserves the comments/history split CONTEXT.md never wanted —
  a person reads one chronology across two taps. Enrichment and the merge are independent wins; do both.
- **Coalesce/filter the flood** (group Splits into "Added N subtasks", drop `pinned` toggles, de-dup the
  same-instant `Created`+`ParentAssigned` pair). Explicitly declined: the flood is accepted, and coalescing
  is real logic (grouping keys, count rollups, burst windows) with its own failure modes; per-row enrichment
  alone makes each row informative. The upgrade path if the flood ever proves unbearable.
- **Show old→new for `Updated`.** Impossible without a server change — the wire carries field *names* only.
  Named as the constraint (a candidate server ask), not a blocker; v1 names the field.
- **Tappable peer titles / full ancestor navigation from a row.** Additive; the ADR-0044 connected-parent
  header already navigates. v1 peer titles are read-only text.
- **Name the tab "Activity".** Collides with the global **Activity** Destination — the overload CONTEXT.md
  spends a section on. "Trail" (audit-/paper-trail) disambiguates.
- **Leading Material icons.** ADR-0045 already rejected adding the material-icons artifact; unicode glyphs
  need no dependency.

Cross-references ADR-0043 (offline-first comments + cached item history — the feed this re-merges),
ADR-0044 (the tabbed body it supersedes + the connected-parent header + reveal-token pattern it reuses),
ADR-0045 (the Android FAB add-actions + the no-material-icons constraint), and ADR-0003/0004 (native UI
per platform — why iOS/macOS SwiftUI is a separate follow-up).

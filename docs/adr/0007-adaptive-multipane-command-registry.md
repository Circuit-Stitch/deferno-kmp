# Adaptive multi-pane UI + shared command registry (Android, iPad, desktop)

**Context.** Strong large-screen support is a first-class goal — Android tablets/Chromebooks, iPad,
and desktop — over the shared-presentation core (ADR-0003). A stretched phone UI is an explicit
non-goal.

This ADR originally described the navigation model as "list + detail as co-resident slots," which
was only ever the shape of *one* surface (Tasks). Read literally it implied the whole app is two
panes, which is wrong: the client has **many top-level [[Destination]]s** (Plan, Calendar, Agenda,
Dashboard, All Tasks, Tasks, Workspaces, Groups, Profile, Permissions, Settings, …). This revision
separates the three navigation tiers so "co-resident, not a stack" is scoped to the one tier where
it is actually true. The shell that sits *above* these tiers (Auth vs Main) is ADR-0013.

**Decision.**

- **Native size-class-driven layout per platform:** Android **Material 3 adaptive**
  (`WindowSizeClass` · `NavigationSuiteScaffold` · `ListDetailPaneScaffold`); iOS **`NavigationSplitView`**
  + size classes; desktop large-screen panes.

- **Navigation has three tiers; they are distinct and must not be conflated:**

  - **Tier 1 — [[Destination]]s.** The top-level navigable places, switched **laterally** via the
    nav suite. Switching is **state-preserving (multiple back stacks)**: each Destination retains its
    own tier-3 stack, so leaving Tasks for Calendar and returning restores Tasks exactly. Tier-1
    switching **never** resets another Destination's inner state. This is *not* a single global back
    stack.
  - **Tier 2 — [[Pane]]s.** Within a Destination that has a list/detail shape (e.g. Tasks), the
    shared Decompose component models list + detail as **co-resident slots — *not* a push/pop stack** —
    and the native View renders **1 or 2 panes by size class** (`ListDetailPaneScaffold`). **This tier,
    and only this tier, is where "co-resident, not a stack" holds.** Single-pane Destinations (Calendar,
    Dashboard, Agenda) have no tier-2 split.
  - **Tier 3 — drill-downs / modals.** Within a Destination, deeper navigation **is** a push/pop
    stack (Decompose `ChildStack`): Settings → subview → sub-subview, "Task create" over the list, etc.
    Each Destination owns its own tier-3 stack (the state tier-1 preserves).

  The original "models list + detail as co-resident slots" claim survives **only as the tier-2
  rule**. It is not a statement about the app's navigation as a whole.

- **A shared command registry** (`create task`, `complete`, `add to plan`, …) is the **single binding
  surface** for keyboard shortcuts, context menus (right-click / long-press), drag-and-drop, the AI
  agent, and OS intents (Android App Actions, iOS App Intents / Siri) — defined and tested once in the
  core.

- **Desktop-class input is a v1 acceptance criterion, not polish:** keyboard shortcuts + focus order;
  pointer hover, right-click, pointer icons; drag-to-reorder/reparent; and **resizable / multitasking
  windows** — Chromebook freeform & split-screen, iPad Split View / Slide Over / Stage Manager —
  handled without losing state.

- **The iOS app is universal (iPhone + iPad)** and size-class-adaptive from day one.

**Consequences.** The navigation model can never assume a single visible screen, nor a single tier:
a View renders the panes of *one* Destination, while the Main shell (ADR-0013) hosts the full set of
Destinations and the per-Destination tier-3 stacks. Views stay thin renderers of shared slots/stacks;
and all input modalities plus agents/intents converge on one shared command surface.

**Rejected.**

- A phone-first navigation stack stretched onto large screens.
- A single global back stack across Destinations (would lose per-Destination state on every lateral
  switch — rejected in favour of multiple back stacks).
- Describing the whole app as "list + detail" (the original framing — corrected here because it
  hid tier 1 and led an agent to assume the app has only two views).

# Adaptive multi-pane UI + shared command registry (Android, iPad, desktop)

**Context.** Strong large-screen support is a first-class goal — Android tablets/Chromebooks, iPad,
and desktop — over the shared-presentation core (ADR-0003). A stretched phone UI is an explicit
non-goal.

**Decision.**
- **Native size-class-driven layout per platform:** Android **Material 3 adaptive**
  (`WindowSizeClass` · `NavigationSuiteScaffold` · `ListDetailPaneScaffold`); iOS **`NavigationSplitView`**
  + size classes; desktop large-screen panes.
- **The shared Decompose nav models list + detail as co-resident slots** — *not* a push/pop stack —
  and each native View renders **1 or 2 panes by size class**. This is the load-bearing constraint
  that makes large-screen support real rather than a stretched phone layout.
- **A shared command registry** (`create task`, `complete`, `add to plan`, …) is the **single binding
  surface** for keyboard shortcuts, context menus (right-click / long-press), drag-and-drop, the AI
  agent, and OS intents (Android App Actions, iOS App Intents / Siri) — defined and tested once in the
  core.
- **Desktop-class input is a v1 acceptance criterion, not polish:** keyboard shortcuts + focus order;
  pointer hover, right-click, pointer icons; drag-to-reorder/reparent; and **resizable / multitasking
  windows** — Chromebook freeform & split-screen, iPad Split View / Slide Over / Stage Manager —
  handled without losing state.
- **The iOS app is universal (iPhone + iPad)** and size-class-adaptive from day one.

**Consequences.** The navigation model can never assume a single visible screen; Views stay thin
renderers of shared slots; and all input modalities plus agents/intents converge on one shared
command surface.

**Rejected.** A phone-first navigation stack stretched onto large screens.

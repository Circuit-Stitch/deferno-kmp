# Plan task detail is a tier-3 drill-down, not a shell overlay

**Context.** ADR-0007 defines three navigation tiers (tier-1 lateral [[Destination]] switch ·
tier-2 co-resident [[Pane]]s · tier-3 in-Destination drill-down), and ADR-0015 gave the [[Main shell]]
a single **overlay slot** above the chrome for genuinely-modal surfaces (Search, New, Feedback, Brain
dump). Opening a [[Task]] from the Plan Destination — and drilling into its subtasks — was routed
through that overlay slot. Because the overlay renders **above the whole chrome**, the navigation
drawer and top bar were unreachable until you backed out, and each subtask step re-keyed the same modal
slot rather than pushing a back stack. A task detail is not modal: it is a place you drill *into* and
navigate *from*, so modelling it as an overlay was a tier mismatch.

**Decision.** Model the Plan Destination's detail as a **tier-3 `ChildStack`** owned by the shell and
scoped to the Plan Destination child — `PlanChild.Dashboard | PlanChild.Detail` over a
`PlanConfig.Dashboard | PlanConfig.Detail` stack in `MainShellComponent`, mirroring the existing
Settings list→category drill-down. Detail renders **inside the chrome body** (the drawer and top bar
stay live), subtask drilling is a real back stack, and back pops subtask → parent → dashboard. The
stack is **retained across tier-1 switches**, so it is per-Destination and state-preserving (ADR-0007
tier-1 / ADR-0013). `OverlayRoute.TaskDetail` / `OverlayChild.TaskDetail` are removed; the overlay slot
keeps **only** the genuinely-modal surfaces (Search, New, Feedback, Brain dump).

The stack is owned by the **shell**, not by `feature/plan` — the shell is the composition layer that
already wires both the `PlanComponent` and the `TaskDetailComponent`, so it can hold a stack over them
with **no `feature → feature` dependency** (ADR-0004's NiA rule). This refines ADR-0007 tier-3 and
ADR-0015: the overlay slot is for modality, the in-Destination drill-down is a tier-3 stack the chrome
renders around.

**Consequences.** The drawer opens directly from a task detail (verified on a Pixel 4 XL), and subtask
navigation is an honest back stack instead of a re-keyed modal. The chrome now reads the active
`PlanChild` to compute its title and leading affordance (a single Task drilled-in shows the Task name +
a back arrow — see ADR-0031). The iOS/macOS Kotlin bridges expose the Plan stack (`PlanStackBridge` +
Plan child accessors); the SwiftUI render of it is a follow-up tracked for the Mac (#216). Any future
list/detail Destination that is **not** multi-pane follows the same pattern (a per-Destination tier-3
stack), rather than reaching for the overlay slot.

**Rejected.**

- **Keep task detail in the overlay slot** — the surface that this ADR exists to move; it makes the
  drawer unreachable and turns subtask drilling into modal re-keying, neither of which a drill-down
  should do.
- **Give `feature/plan` its own internal detail stack** — the detail's `TaskDetailComponent` and the
  dashboard's `PlanComponent` are wired by the shell; pulling the stack into the slice would either
  duplicate that wiring or introduce a `feature → feature` edge (ADR-0004). The shell already sees both
  slices, so it is the natural owner.

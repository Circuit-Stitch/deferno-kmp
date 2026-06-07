package com.circuitstitch.deferno.desktop.shell

/**
 * A top-level [[Destination]] in the Main shell (ADR-0013 / ADR-0007 tier 1) — a place switched
 * **laterally** via the nav suite, each retaining its own drill-down state (multiple back stacks).
 *
 * This enum is the **Destination registry**: the Main shell renders one nav item per entry and never
 * assumes a fixed count, so the dozen-plus future Destinations (Calendar, Agenda, Dashboard, All
 * Tasks, Workspaces, Groups, Profile, Permissions, Settings, …) slot in by adding an entry here (plus
 * its child in [MainShellComponent] and its renderer in the Main shell View). The declaration order is
 * the nav order; [Plan] is the home Destination (design-principles.md: "open into the Plan"). The
 * human label + icon are a View concern and live in the Compose layer, not here.
 */
enum class Destination {
    Plan,
    Tasks,
}

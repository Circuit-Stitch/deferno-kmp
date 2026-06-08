package com.circuitstitch.deferno.shell

/**
 * A top-level [[Destination]] in the Main shell (ADR-0013 / ADR-0007 tier 1) — a place switched
 * **laterally** via the nav suite, each retaining its own drill-down state (multiple back stacks).
 *
 * This enum is the **Destination registry**: the Main shell renders one nav-suite item per entry and
 * never assumes a fixed count, so the deferred future Destinations (Agenda, Dashboard, Workspaces,
 * Groups, Permissions, … — ADR-0015) slot in by adding an entry here (plus its child in
 * [MainShellComponent] and its renderer in the Main shell View). The declaration order is the
 * nav-suite order; [Plan] is the home Destination (design-principles.md: "open into the Plan"). The
 * human label + icon are a View concern and live in the Compose layer, not here.
 *
 * **v1 set (ADR-0015):** [Plan] · [Calendar] · [Tasks] · [Profile] · [Settings]. Each carries a
 * [slot] that drives the adaptive nav suite: on a **compact** window the bottom bar shows the
 * [NavSlot.Primary] Destinations plus a **"More"** overflow onto the [NavSlot.Secondary] ones; on
 * **medium/expanded** the rail/drawer lists every Destination directly and "More" disappears. "More"
 * is **not** a Destination — the secondary screens stay tier-1 peers with their own back stacks
 * (ADR-0015: not tier-3 children of a "More" Destination).
 */
enum class Destination(val slot: NavSlot) {
    Plan(NavSlot.Primary),
    Calendar(NavSlot.Primary),
    Tasks(NavSlot.Primary),
    Profile(NavSlot.Secondary),
    Settings(NavSlot.Secondary),
}

/**
 * Where a [Destination] sits in the adaptive nav suite (ADR-0015): a [Primary] Destination is always
 * directly reachable (a bottom-bar item on compact, a rail/drawer item otherwise); a [Secondary]
 * Destination is reached via the compact-only **"More"** overflow, and listed directly on rail/drawer.
 */
enum class NavSlot { Primary, Secondary }

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
 * **v1 set (ADR-0015 + Inbox amendment + ADR-0040 Assistant):** [Plan] · [Calendar] · [Tasks] ·
 * [Assistant] · [Inbox] · [Profile] · [Settings]. [Assistant] is the one **conditionally-present**
 * Destination — the shell omits it from the rendered registry unless the Org is `entitled` (ADR-0040), so
 * a `when` over this enum stays total but the nav suite varies. Each carries a
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
    // The Assistant: the server-mediated conversational AI Destination (ADR-0040, #282), ordered right
    // after Tasks. CONDITIONALLY PRESENT — rendered only when the Org is `entitled`; the shell's
    // `destinations` filters it out otherwise (every `when` over Destination stays total regardless).
    Assistant(NavSlot.Secondary),
    // The Inbox: the triage queue for persisted Brain dump draft Tasks (ADR-0015 Inbox amendment). A
    // Secondary peer (reached via "More" on compact), ordered first among the secondaries.
    Inbox(NavSlot.Secondary),
    // Activity: a global cross-surface action ledger (every action across what's visible to the person —
    // MCP / Website / Mobile app / Google Voice). A PLACEHOLDER for now (a ComingSoon body) until the
    // global history feed lands — tracked in #260. Still a real Secondary Destination with its
    // own retained back stack, so the slice drops in later with no structural change (ADR-0015).
    Activity(NavSlot.Secondary),
    Profile(NavSlot.Secondary),
    Settings(NavSlot.Secondary),
}

/**
 * Where a [Destination] sits in the adaptive nav suite (ADR-0015): a [Primary] Destination is always
 * directly reachable (a bottom-bar item on compact, a rail/drawer item otherwise); a [Secondary]
 * Destination is reached via the compact-only **"More"** overflow, and listed directly on rail/drawer.
 */
enum class NavSlot { Primary, Secondary }

# Navigation shell: Auth vs Main, and a Destination graph with multiple back stacks

**Context.** ADR-0007 (as revised) defines the three navigation tiers *inside* the running app, but
not what sits above them. Two things were missing and had to be made explicit after an agent assumed
the app has only two views:

1. There is a **shell boundary** above all Destinations. Before an [[Account]] is signed in there is
   no [[Active Account]], no [[Org]] scope, and no nav suite — login is **not** a Destination.
2. The top tier (Destinations) is **state-preserving**, which is a real architectural commitment
   (multiple back stacks), not a default.

`feature/auth` already exists and is pre-Account; the Destinations (Plan, Tasks, Calendar, …) are all
post-Account and scoped to the Active Account + active [[Workspace]]. Without a recorded shell model,
the auth surfaces and the Destinations get flattened into one undifferentiated list of "screens."

**Decision.**

- **The root is a two-state [[Shell]], selected by auth state** (a Decompose root that swaps its
  child):
  - **Auth shell** — pre-Account: sign-in, MFA challenge, account picker. Owns its own navigation;
    holds no Org scope.
  - **Main shell** — post-Account: hosts the **Destination graph** (`NavigationSuiteScaffold`),
    scoped to the **Active Account + active Workspace**.
- **The Auth shell is re-entrant from the Main shell.** Fast user switching (changing the Active
  Account) and adding an account drop back into the Auth flow, then return to a Main shell bound to
  the newly Active Account. Account isolation (ADR-0002) means the Main shell is rebuilt per Account,
  not mutated across the boundary.
- **Destinations form a multiple-back-stack graph.** Each Destination owns its own tier-3 `ChildStack`
  (ADR-0007); the Main shell switches between Destinations **laterally and state-preservingly** — it
  does not pop or reset the Destination being left. Tier-1 switching is not a global back stack.
- **Login/MFA/account-picker are never Destinations**, and Destinations never appear in the Auth shell.
- **Per-scene, per ADR-0008.** Each window/scene gets its own shell + Destination graph
  (presentation is scene-scoped); the data layer stays a process-global singleton (G2), and Account
  context is scene-scoped (G3) so a future second window can show a different Active Account.

**Consequences.** The app's "how many views" answer is structural, not a flat count: one of two
shells is active; inside the Main shell, one of N Destinations is foreground; inside a Destination,
0–2 Panes (tier 2) and a private drill-down stack (tier 3). State retention (ADR-0008 G5) plus the
multiple-back-stack rule means lateral Destination switching and arbitrary window resize never drop
state. The seam for multi-Account-per-window (ADR-0008 G3) lives at the shell boundary.

**Rejected.**

- **Login as a Destination inside the nav suite.** Would imply an Org scope and Active Account that
  don't exist pre-Account, and would let the nav suite render before authentication.
- **A single shared back stack across Destinations.** Loses per-Destination state on every switch;
  rejected for multiple back stacks (also ADR-0007).
- **A flat list of "screens" with no shell/Destination/Pane distinction.** This is precisely the
  framing that led an agent to conclude the app has two views; recording the structure prevents the
  recurrence.

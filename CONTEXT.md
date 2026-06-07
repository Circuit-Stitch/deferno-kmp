# Deferno Client

The native Deferno client — Android first, with iOS and desktop to follow over a shared
Kotlin Multiplatform core. This glossary defines only the language specific to the *client*.
The core Deferno domain language (Item, Task, Habit, Chore, Event, Occurrence, Org, Plan,
Workspace, Canonical ref, Alias, Subtask/Child, …) is owned by the **Deferno backend's
`CONTEXT.md`** (the canonical source, in the `Kyle-Falconer/Deferno` repo) and is not
duplicated here — this file only adds client-specific terms and disambiguations.

## Language

### Identity & switching

**Account**:
One Deferno identity the person has signed into the app — its own credentials (login name +
password), its own bearer token, and its own [[Org]] memberships. The unit the person adds,
removes, and switches between. Accounts are **fully isolated** from one another: no data, auth,
or visibility ever crosses between them.
_Avoid_: profile, login, user (a "user" is the backend identity an Account signs in *as*), workspace.

**Active Account**:
The Account a window is showing and syncing — conceptually **one per window** (a single window in
v1; multi-window is reserved, not yet built). Changing it is "fast user switching."
_Avoid_: current user, selected profile.

**Account switch**:
Changing which Account is the [[Active Account]]. Distinct from changing [[Org]]: a switch crosses
the isolation boundary into a different identity, whereas an Org change stays within one Account.
_Avoid_: re-login (a switch need not re-authenticate), profile swap.

### Account vs Org vs User (disambiguation)

These three are routinely conflated; in this client they are kept distinct:

- **Account** — a *client* concept: one signed-in Deferno identity on this device. The person may
  hold several (e.g. work, personal, HOA), each with different credentials and [[Org]] memberships.
  The **hard isolation boundary**.
- **Org** *(inherited)* — a *within-Account* ownership boundary. One Account belongs to one or more
  Orgs (a [[Personal org]] plus any shared ones); every Item carries `owner_org_id`. Selecting an Org
  filters what you see *inside* the [[Active Account]]; it never crosses accounts.
- **User** *(inherited, backend)* — the server-side identity (`GET /auth/me`) an Account authenticates
  as. One Account ⇄ one backend User.

### Item state (disambiguating "status")

The API overloads `status` across three unrelated axes (and ships six wire enums with inconsistent
casing). The client names them distinctly and condenses them in the DTO→domain mapper; the wire
DTOs stay faithful, the domain types are clean.

**Working state**:
A [[Task]]'s progress through its own lifecycle — Open, In-progress, In-review, Done, Dropped.
_Avoid_: status, task status.

**Definition state**:
The "light switch" on a recurring definition ([[Habit]]/[[Chore]]/[[Event]]) — whether it is live or
shelved: Active or Archived. (The API also carries `in-review`; retained faithfully but its meaning
is unresolved.) This is the state of the *definition*, never of any single firing.
_Avoid_: status, DefStatus, archived flag.

**Occurrence state**:
How one dated firing (an [[Occurrence]]) of a recurring definition went — Scheduled, In-progress,
Done (with on-time / late punctuality), Skipped, Missed. The client only ever *writes* a coarse
action (start / complete / skip); the finer read states — Scheduled, Missed, and the on-time/late
split — are **server-derived**.
_Avoid_: status, OccurrenceStatus, done_on_time/done_late.

### Credentials

**Personal access token (PAT)**:
The opaque bearer credential an [[Account]] stores in its secure vault and sends as `Authorization:
Bearer`. The **durable native-client credential**: OAuth/PKCE (when built) is only one way to *mint*
one, not a credential the client persists (ADR-0012). Minted/revoked via the web client's User
Settings or `…/auth/tokens`.
_Avoid_: access token, OAuth token, session token, API key. (The wire/spec name is "API token"; the
product term is **PAT**.)

### Navigation surface

The client UI is **not** a flat list of screens. It has a fixed structure — a [[Shell]] above many
[[Destination]]s, each of which may show co-resident [[Pane]]s and its own drill-down stack (the
three tiers in ADR-0007; the shell split in ADR-0013). The terms below keep these levels distinct so
"how many views are there" has a structural answer, not a number.

**View**:
The thin, per-platform **native renderer** of shared component state — the *V* in the shared-state /
View / command split (ADR-0003). A View holds no business logic; it renders slots/stacks and emits
events/commands (e.g. `TaskListScreen`, `TaskDetailScreen`). Many Views compose one [[Destination]].
_Avoid_: using "view" for a navigable place (that is a [[Destination]]) or for the backend
[[Workspace]] (the backend reserves "view" for that concept) — "View" here is **only** the renderer.

**Destination**:
A **top-level navigable place** in the [[Main shell]], switched laterally via the nav suite (Plan,
Calendar, Agenda, [[Dashboard]], All Tasks, Tasks, [[Workspace]]s, Groups, Profile, Permissions,
Settings, …). Switching between Destinations is **state-preserving**: each keeps its own drill-down
stack (multiple back stacks, ADR-0007 tier 1). "All Tasks" is the rendering of the backend
[[Personal org]].
_Avoid_: view, screen, tab, page.

**Pane**:
A **co-resident region within a single [[Destination]]** — e.g. the list pane and detail pane of
Tasks, shown as 1 or 2 panes by window size class (ADR-0007 tier 2). Panes are co-resident slots, not
a back stack. Only list/detail-shaped Destinations have Panes; single-pane Destinations (Calendar,
Dashboard) have none.
_Avoid_: screen, view, window.

**Shell**:
The **root container above all [[Destination]]s**, in one of two states by auth status (ADR-0013):
the **Auth shell** (pre-[[Account]]: sign-in, MFA challenge, account picker — no Org scope, no nav
suite) and the **Main shell** (post-Account: hosts the Destinations, scoped to the [[Active Account]]
+ active [[Workspace]]). Login is never a Destination. The Auth shell is re-entered from the Main
shell for [[Account switch]] / add-account.
_Avoid_: screen, root view.

**Dashboard** *(client)*:
An **always-global at-a-glance overview** [[Destination]] (e.g. progress, counts, mood, upcoming).
It summarizes across **all** Orgs and **ignores** the active [[Workspace]] scope — the one place that
does. Distinct from a [[Workspace]], which *selects* scope rather than summarizing.
_Avoid_: workspace, home, board.

**Workspace** *(client rendering of the backend concept)*:
The backend's [[Workspace]] — a per-user saved view scoping a curated set of [[Org]]s and their items
— rendered in this client as a [[Destination]] (to manage/switch Workspaces) and as the **active
scope** the Org-scoped Destinations honour. The definition is owned by the backend `CONTEXT.md`; this
entry only records how the client surfaces it.
_Avoid_: dashboard (that is the global overview [[Destination]], a different thing), view, board, lens.

### Commands

**Command** *(client)*:
A first-class, pure-data **user intent** (ADR-0007) — *complete*, *add to plan*, *rename*, … — that a
[[View]], the AI agent, or an OS intent issues and the [[Command registry]] routes to a single [[Task]]
write action. The **single binding surface** every input modality shares: keyboard shortcuts, context
menus (right-click / long-press), drag-and-drop, agents, and OS intents (Android App Actions, iOS App
Intents / Siri) — defined and tested once in the core, never re-derived per platform.
_Avoid_: action, event, handler; **Mutation** (the wire-level outbox intent a Command *dispatches to*,
ADR-0001 — a Command is the user-facing verb, a Mutation is its replayable HTTP effect).

**Command registry** *(client)*:
The shared, enumerable **catalog of every [[Command]]** plus the one place each maps to its action —
what a command palette lists, a context menu filters, and the agent / OS-intent layer binds to. It sits
above the offline-first write seam, so issuing a Command applies optimistically and enqueues for replay
(ADR-0001). It is not a UI; the [[View]]s and the agent are its *clients*.
_Avoid_: dispatcher, bus, controller, menu.

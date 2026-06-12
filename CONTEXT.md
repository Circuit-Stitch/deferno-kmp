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
_Avoid_: login, user (a "user" is the backend identity an Account signs in *as*), workspace; and do
**not** call the Account a "profile" — the [[Profile]] Destination renders the [[User]]'s identity (a
different thing), and account controls merely sit alongside it.

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

**Priority** *(client, derived — never stored)*:
A reading **derived** from a [[Task]]'s existing attributes — `desire` (how much the person wants to
do it), `productive` (how productive it feels), and `completeBy` (urgency) — wherever the client
needs to rank or emphasize. There is **no priority field** in the domain and the client must not
invent one (no P0–P3 enum, no priority labels): anything that extracts or ranks "by priority" writes
or reads those three attributes.
_Avoid_: priority as a stored field, priority labels ("p1"), importance (use the specific attribute).

**Dependency** *(client, disambiguation — say sequence or decomposition)*:
The domain has **no blocks/blocked-by edge**. What look like "dependencies" are two existing
relations: a **sequence** (an ordered chain via `nextTaskId` — "first X, then Y", the chain
`fold`/promote manipulate) and a **decomposition** (a parent/child tree via `parentId` — "Y is part
of Z", the backend's [[Subtask/Child]]). Anything extracting or displaying "dependencies" uses these
two; a true cross-chain blocker relation does not exist and must not be faked with labels or notes.
_Avoid_: dependency, blocker, blocked-by, prerequisite (name the relation: sequence or decomposition).

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
A **top-level navigable place** in the [[Main shell]], switched laterally via the nav suite. The
**v1 set is Plan, Calendar, Tasks, Profile, Settings** (Plan is the home Destination). **Workspaces,
[[Group]]s, Permissions, Agenda, and [[Dashboard]] are reserved for later** — kept in this glossary
but not built in v1: Workspaces/Groups/Permissions are blocked on the backend's future **Groups
subsystem ("Spec 2")** — there is no Workspace, org-listing, or permissions API today, and a user has
exactly one knowable [[Org]] (`personal_org_id`). Switching between Destinations is
**state-preserving**: each keeps its own drill-down stack (multiple back stacks, ADR-0007 tier 1).
There is no separate "All Tasks" destination: it is simply **Tasks scoped to the Personal
[[Workspace]]** (the [[Personal org]]) — degenerate in v1, where that is the only Org.
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
shell only for **add-account or re-authentication**; a plain [[Account switch]] to an Account whose
[[Personal access token (PAT)]] is already stored happens **in place** (a Main-shell switcher →
`switchTo`, no Auth shell, no re-auth).
_Avoid_: screen, root view.

**Dashboard** *(client)*:
An **always-global at-a-glance overview** [[Destination]] (e.g. progress, counts, mood, upcoming).
It summarizes across **all** Orgs and **ignores** the active [[Workspace]] scope — the one place that
does. Distinct from a [[Workspace]], which *selects* scope rather than summarizing.
_Avoid_: workspace, home, board.

**Profile** *(client)*:
The [[Destination]] that renders the **display identity of the [[User]]** the [[Active Account]] signs
in as — avatar, name, `@handle`, email, and the Orgs the User belongs to (`/auth/me`). The
**[[Account]] controls co-located** on this screen — which Account is active, this device's
[[Personal access token (PAT)]], **Sign out** — are *not* "the profile"; they sit alongside the
identity. Distinct from the **avatar account switcher** (the quick-switch surface on every
Destination's app bar): Profile is the full identity-and-management hub reached via More, the switcher
is the fast [[Account switch]].
_Avoid_: account (the switching unit, a different thing), settings (app preferences, a separate
[[Destination]]), the avatar switcher.

**Workspace** *(deferred — client rendering of a future backend concept)*:
A per-user saved view scoping a curated set of [[Org]]s and their items — *intended* to be rendered
in this client as a [[Destination]] (to manage/switch Workspaces) and as the **active scope** the
Org-scoped Destinations honour. **Not built in v1:** the backend exposes no Workspace API and no
org-listing endpoint, so the client cannot enumerate Orgs to scope, and the inline "workspace
selector" is omitted (a user has one knowable [[Org]]). Arrives with the backend Groups subsystem
("Spec 2"). The definition is owned by the backend `CONTEXT.md`; this entry records how the client
*will* surface it.
_Avoid_: dashboard (that is the global overview [[Destination]], a different thing), view, board, lens.

**Group** *(deferred — client term for a shared Org with people)*:
The client-facing rendering of a **shared (non-personal) [[Org]]** together with **its members and
their roles** (Owner / Admin / Member) — the *people* side of an Org. The Personal org is **never** a
Group (it has no other members). One Account may belong to several Groups. **Not built in v1:** the
backend's `group_id` is reserved and ignored ("future Groups subsystem, Spec 2"), and there is no
member/role/invite API; `UserRole` is only a system-wide `user|admin`, not a per-Group role. The
**Permissions** Destination (group → member roles) is the management surface for this and is likewise
deferred.
_Avoid_: Org (a Group is a shared Org *surfaced with its people*; Org is the ownership-boundary term),
[[Workspace]] (a Workspace *selects a set of* Orgs/Groups; a Group *is* one), team, household (those
are example Group names, not the type).

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

### Agent

**Agent** *(client)*:
The client-side capability that turns the person's own context into **proposals** — today two
propose-only services, the [[Extractor]] and the producer of the [[Plan proposal]], running against
the configured [[Inference engine]]. It holds no write access and **never commits anything**: the
person accepts a proposal, and that acceptance commits through the ordinary write path. Reserved to
grow into the Command-issuing agent the [[Command registry]] anticipates.
_Avoid_: assistant, copilot, bot, "the AI" (name the capability or the specific service).

**Extractor** *(client)*:
The propose-only [[Agent]] service that turns a [[Brain dump]] [[Transcript]] into **draft
[[Task]]s** — titles, decompositions (parent/child), sequences (next-task chains), `completeBy`, and
`desire`/`productive`. Its output is always drafts for the person's review, never committed work.
_Avoid_: parser, brain-dump AI, voice commands (routing speech to Commands — a non-goal).

**Plan proposal** *(client)*:
The [[Agent]]'s proposed **delta against the day's seeded plan** — backlog [[Task]]s worth adding
(ranked by the derived [[Priority]]), removals when the day overflows, and an ordering — reviewed on
the Plan [[Destination]]. Accepted entries commit through the ordinary plan verbs; the proposal is
**never auto-applied**, and the server-seeded plan remains the substrate it amends.
_Avoid_: generated plan, AI plan (the Agent *curates* the seeded day; it does not author it).

**Inference engine** *(client)*:
The configured backend the [[Agent]] runs against — the **Deferno relay** (Deferno-operated,
per-[[Account]] entitlement) or a **local engine** on this device. The choice is an [[App setting]]
(device-local, like the speech-engine choice); an off-device engine is **explicit opt-in, never
silent**. Distinct from the [[Dictation]] speech engine — speech recognition never has a cloud tier.
_Avoid_: model (an engine *hosts* a model), provider, speech engine (a different catalog).

### Voice & dictation

**Dictation** *(client)*:
Speaking to fill a text field — the microphone affordance on a text input (in v1, the **New** create
surface's title/notes) that turns on-device speech into a [[Transcript]] streamed into the focused
field. The person still chooses the item kind **explicitly** (ADR-0015); dictation **never infers
structure** (no kind, count, due date, or tags). The product is editable text, nothing more.
_Avoid_: voice commands (routing speech to the [[Command]] registry — a deliberate non-goal, a
different capability), transcription (the act; the *product* is the [[Transcript]]), recording (audio
is transient and never persisted).

**Transcript** *(client)*:
The editable text a [[Dictation]] produces — partial words stream in, a final result settles, and from
that point it is ordinary editable text the person owns. The single hand-off artifact between the
on-device speech layer and whatever consumes it (in v1, a text field; later, the [[Brain dump]]
extractor). Always **on-device**: the audio never leaves the device and is never sent to a server for
recognition.
_Avoid_: recording (the transient audio, not kept), recognition result, dictation (that is the *act*).

**Preflight** *(client, desktop)*:
The **prompting resolution of [[Dictation]]'s two OS permission gates** — speech recognition first,
then the microphone, the same order the [[Sidecar helper]]'s own first-use prompts fire in (specified
in the [[Sidecar protocol]]) — run by the speech port before the Helper's mic engages. An undetermined
gate fires the real OS prompt; a settled denial blocks and names **which** gate; an unknown state stays
clear so the first real subscribe can still prompt. Owned by the port: OS permission vocabulary (TCC
states) never crosses it. Distinct from **readiness**, the introspect-only signal that never prompts.
_Avoid_: permission check (ambiguous between this and readiness introspection), TCC (the macOS
implementation detail behind the seam).

**Brain dump** *(deferred)*:
Free-form spoken capture whose [[Transcript]] the [[Extractor]] turns into **several draft
[[Task]]s** the person reviews before anything is created — Stage 2, **not built in v1**. Unlike
[[Dictation]] (which fills one field and infers nothing), a brain dump is inference-heavy and will
relax ADR-0015's "kind is explicit, never inferred." The drafts are exactly that: *drafts*, never
auto-committed. The extractor **never runs silently**: an off-device model only by the person's
**explicit opt-in**, and when a local engine is available it is preferred by default (the audio
itself never leaves the device — that boundary is [[Transcript]]'s and stays absolute).
_Avoid_: [[Dictation]] (fills one field, no inference), voice commands (a non-goal).

### Native sidecar (desktop)

How the **desktop** client reaches OS-native capabilities the JVM can't (on-device dictation, OS
permission prompts, notifications, a menu-bar item) — a separate native process over a local socket,
not JVM→native FFI (ADR-0024/0025).

**Sidecar** *(client, desktop)*:
A separate **OS-native helper process** the desktop client reaches over a local socket to use
capabilities the JVM can't. A **pattern**, not one program: macOS uses a Swift helper today; Windows and
Linux helpers follow over the *same* contract. The JVM never links native code — it talks to the helper.
_Avoid_: plugin, extension, daemon (the lifecycle is per-OS — launchd on macOS, etc.); "the Swift app"
(macOS is one instance of the pattern).

**Sidecar helper** (the **Helper**) *(client, desktop)*:
A concrete **per-OS implementation of the server half** of the [[Sidecar protocol]] — the macOS Swift
helper now; Windows/Linux helpers later. It owns the OS-native capabilities and its own permission
identity (TCC on macOS, ADR-0024), binds the socket, and speaks the one contract.
_Avoid_: [[Sidecar]] (that is the pattern), server (overloaded with the backend), plugin.

**Sidecar client** *(client, desktop)*:
The **OS-agnostic JVM half** — one implementation across every desktop OS. It connects to whatever
[[Sidecar helper]] binds the socket path, performs the peer-auth handshake, and multiplexes
request/response, server streams, and push. Helper-agnostic: in tests a [[Stub helper]] stands in.
_Avoid_: SDK, driver.

**Sidecar protocol** *(client, desktop)*:
The **language-neutral** JSON-over-socket contract every [[Sidecar helper]] implements — frame shapes,
correlation, error model, handshake, capability negotiation (ADR-0025; spec in `contracts/sidecar/`). The
shared substrate that lets a new OS add a [[Sidecar helper]] without a new protocol.
_Avoid_: API (the backend's REST surface is "the API"), format.

**Stub helper** *(client, desktop, testing)*:
A **canned, non-native** [[Sidecar helper]] that binds a real socket and serves fixed frames — the
*reference implementation of the server half*, used to exercise the whole [[Sidecar client]] path on the
Linux fast path with no Mac (ADR-0024 tracer bullet). Not a real capability provider.
_Avoid_: mock (it binds a real socket and speaks the real protocol), fake.

### Settings (app vs user)

The **Settings** [[Destination]] surfaces two kinds of preference that look alike on screen but differ
in *where they live and how far they travel*. Keeping them distinct stops "a setting" from silently
implying "synced."

**User setting** *(synced)*:
A per-[[Account]] preference **synced with the backend**, offline-first through the outbox (ADR-0001) —
the `UserSettings` model (appearance, task behaviour, …). It follows the [[Account]] across devices and
is scoped to the [[Active Account]].
_Avoid_: app setting (the device-local kind), preference (ambiguous between the two).

**App setting** *(client, device-local)*:
A preference scoped to **this install on this device** — **never synced, never crossing [[Account]]s**.
Covers OS/app permissions, device notification preferences, and the [[Dictation]] **speech-engine
choice**. Lives in a device-local store, *not* in [[User setting]]s, because the thing it configures is a
device capability (an engine available on one device may be absent on another). Surfaced on the same
**Settings** [[Destination]] alongside [[User setting]]s.
_Avoid_: [[User setting]] (the synced, per-Account kind), system/OS setting (those live in the platform
Settings app, not Deferno).

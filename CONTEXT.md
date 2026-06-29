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

**Item kind** *(client disambiguation)*:
Task, Habit, Chore, and Event are kinds of one converged [[Item]] family. Treat them as shared Item
capabilities first; name a specific kind only when the domain behavior is genuinely kind-specific.
_Avoid_: separate Task/Habit/Chore/Event lanes, task-only path, divergent kind work.

**Item anchor** *(client)*:
A compact, local-only reference summary returned by [[Reference lookup]] so an [[Agent]] proposal can
attach drafts to existing Items without carrying full Item records. It includes identity and
disambiguation fields only: the human-facing `ref` (`{org_slug}-{sequence}`), kind, title, parent,
deadline, creation date, and description; the client may carry the UUID internally for validation and
commit, but the agent-visible anchor is the `ref`.
_Avoid_: full Item model, history payload, comment payload.

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

**Item tree** *(client)*:
The Tasks [[Destination]]'s rendering of the [[Subtask/Child]] **decomposition** as one nested,
collapsible tree spanning **all [[Item kind]]s equally** (a [[Habit]] may parent a [[Task]], an
[[Event]] may sit under a [[Chore]], …) — the **complete catalog** of the person's work [[Item]]s,
not a flat list. Roots are parent-less items; siblings sort by `sequence`; a child whose parent is
absent from the visible set (e.g. aged out by the [[Done-visibility window]]) renders at the root.
Reparenting and reordering are the single **Move** [[Command]] `(item → new parent, position)`.
Distinct from the [[Plan]] (today's flat, curated list — never a tree) and from the deferred
one-level drill-in it replaces.
_Avoid_: task tree (it spans every kind, not only [[Task]]s), outline, backlog, flat list.

**Done-visibility window** *(inherited, backend; client-honored)*:
The synced [[User setting]] governing how long a **terminal, non-recurring** [[Item]] (a Done/Dropped
[[Task]] or a past one-off [[Event]]) stays visible in lists before it is hidden — two levers: a
longer **global** window (all lists) and a shorter **dashboard** window, each settable from
*immediate* to *never*. Recurring kinds ([[Habit]]/[[Chore]]/recurring [[Event]]) never age out. The
client honors it by syncing the **server-windowed** [[Item]] snapshot rather than re-deriving it.
_Avoid_: archive, retention, auto-delete (the item is only *hidden*, never removed).

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
**v1 set is Plan, Calendar, Tasks, [[Inbox]], Profile, Settings** (Plan is the home Destination), plus
the **[[Assistant]]** Destination — the one **conditionally-present** Destination, included in the nav
suite only when the active [[Org]] is `entitled` (its [[Availability]] must be fetched *before* the nav
suite is built); when entitled-but-not-enabled it renders an in-place enable + egress-consent state,
and chat once `available`. Its addition amends ADR-0015's frozen set. **Workspaces,
[[Group]]s, Permissions, Agenda, and [[Dashboard]] are reserved for later** — kept in this glossary
but not built in v1: Workspaces/Groups/Permissions are blocked on the backend's future **Groups
subsystem ("Spec 2")** — there is no Workspace, org-listing, or permissions API today, and a user has
exactly one knowable [[Org]] (`personal_org_id`). Switching between Destinations is
**state-preserving**: each keeps its own drill-down stack (multiple back stacks, ADR-0007 tier 1).
There is no separate "All Tasks" destination: it is simply **Tasks scoped to the Personal
[[Workspace]]** (the [[Personal org]]) — degenerate in v1, where that is the only Org. Tasks renders
as the [[Item tree]] (the complete catalog of work [[Item]]s, nested and collapsible), not a flat list.
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

**Chrome** *(client)*:
The **single adaptive top bar** the [[Shell]] renders around the foreground in-chrome surface, computed
once as a Compose-free **`ChromeSpec`**(title, drilled, actions) and rendered natively per platform
(Compose on Android/desktop, SwiftUI on iOS — ADR-0031). `drilled` picks the leading affordance (☰ menu
at a [[Destination]] root vs ← back when drilled into a tier-3 detail, ADR-0007); `actions` are the
trailing create affordances (New, Brain dump, per-screen Refresh). Its **title is shell presentation
state**, not a domain label and not the nav-suite label — a Destination's nav row reads "Plan" while its
chrome title reads "Today". Multi-pane Destinations (Tasks) are the carve-out: their co-resident
[[Pane]]s keep their own headers, so the chrome shows only the global actions. The shell's modal
overlay slot (Search/New/Feedback/Brain dump, ADR-0015) sits *above* the chrome and is not driven by it.
_Avoid_: header, app bar, toolbar (those are the per-platform [[View]] renderers of the one Chrome);
"screen chrome" (the chrome is shell-wide, not per-screen).

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

**Inbox** *(client)*:
The [[Destination]] that holds **draft [[Task]]s awaiting the person's accept-or-dismiss**, decoupled
from their capture. A [[Brain dump]] is transcribed and its drafts extracted in the **background**, so
they land here for review **later** rather than inline at capture time — a triage queue the person
**clears out**: *accept* commits a draft as a real [[Task]] through the ordinary online create path
(ADR-0016) and removes it; *dismiss* removes it unaccepted. **State-preserving** (leave mid-triage and
return to the same place, ADR-0013) and individually dismissable, so pending drafts never block other
work and the surface is never in the way. v1 holds **only** the [[Extractor]]'s brain-dump drafts; the
name anticipates a general triage surface, but nothing else routes here yet.
_Avoid_: [[Brain dump]] (the *capture* act — the Inbox reviews its output, it does not capture), drafts
(the content, not the place), [[Dashboard]] (a global summary, not a triage queue), [[Plan]] (today's
curated list, not untriaged captures).

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

**OS intent** *(client)*:
A user intent that arrives from a **caller's own assistant** — Google Assistant / Gemini on Android,
Siri / App Intents on iOS, Alexa, Bixby, or any **MCP-speaking agent** (Claude, …) — rather than from
Deferno's UI. The **caller's** intelligence does the speech recognition and the language understanding
**off-Deferno**; Deferno exposes a **typed tool / intent surface** the caller maps the utterance onto,
and runs **no inference of its own**. **Two routings:**
- **Navigation / read intents** ("open my plan", "what do I have today") bind **deterministically** to
  an existing [[Command]] or [[Destination]] by its stable `CommandId`.
- **Capture intents** ("take out the trash every Tuesday") are categorized **by the caller** filling a
  **behavioral schema** — *occurs at a set time you attend?* (→ [[Event]]); else *repeats?* (no →
  [[Task]]); else *if its time passes undone, does it carry forward or lapse?* (carries forward →
  [[Chore]]; lapses → [[Habit]]). The schema exposes **behavior, never Deferno's kind names**, so the
  caller needs no Deferno taxonomy and the contract stays stable across backend API churn. Deferno
  **derives the [[Item kind]]** from those fields and **commits directly** through the ordinary
  `CreateItem` [[Command]] (offline-first: optimistic local apply + outbox enqueue, an honest "queued"
  result — never "saved on the server").
Because the inference is the **caller's**, not Deferno's [[Agent]], this path does **not** cross
ADR-0027's propose-only boundary (which governs Deferno's *own* inference): an OS intent is just one more
input modality on the [[Command registry]], peer to keyboard / context-menu / drag, and like them it
**commits**. The [[Inbox]] is **not** on this path — it stays the fallback bucket only for a future
capture route where **Deferno itself** must process raw input asynchronously (a low-capability caller
hands plain text → the [[Extractor]] → a propose-only draft to review later).
_Avoid_: [[Agent]] (Deferno's own propose-only inference — an OS intent's categorization is the
*caller's*), voice command (the in-app [[Dictation]] non-goal of routing *Deferno-recognized* speech to
Commands), App Action / App Intent / Siri shortcut / Alexa skill (the per-platform *mechanisms* that
deliver an OS intent, not the concept).

### Assistant (server-mediated)

**Assistant** *(client; backend-mediated)*:
Deferno's **server-side conversational AI** — a multi-turn chat, scoped to one [[Org]], that the
**backend** runs end-to-end: it streams the assistant's reply token-by-token, reads the person's items
server-side, and **applies** changes itself (tagging them `actor = assistant`), gated by a per-Org
**[[Availability]]** check (`entitled && enabled`) and a shared monthly token pool. Distinct from the
[[Agent]]: the Agent is **client-side, propose-only, on-device-first** and commits through the
person's offline outbox; the Assistant is **backend-mediated** — decrypted item content egresses to a
third-party AI sub-processor, so enabling it is the Owner's explicit egress consent. The iOS client is
a **thin chat surface** over the backend's turn stream; it owns no inference. The two capabilities
**share only the propose-then-accept review pattern** (see [[Proposal]]).
_Avoid_: [[Agent]] (the client-side propose-only capability — a different trust and transport model),
copilot, bot, "the AI", chat (the *surface* the Assistant is rendered in, not the capability).

**Availability** *(client; Assistant)*:
The single per-[[Org]] gate every Assistant surface checks: `available = entitled && enabled` —
`entitled` is whether the Org may have the Assistant (billing/staff-set), `enabled` is whether the
Org **Owner turned it on** (which carries the egress consent). When not `available` the chat surface
is hidden, not merely disabled.
_Avoid_: entitlement (only one half), feature flag, the inference-engine [[App setting]] (that gates the
[[Agent]], a different capability).

**Assistant proposal** *(client; Assistant)*:
A **gated change** the [[Assistant]] surfaces mid-turn for the person's explicit yes/no —
`{tool, input, summary}`, where `summary` is the human-legible "exactly what this will do." Only
**destructive, bulk, or cross-[[Org]]** changes become proposals; ordinary writes the Assistant simply
performs itself (tagged `actor = assistant`). Confirming **applies it server-side**
(`POST …/assistant/apply`, re-checked against the same gate); rejecting is purely client-side (discard).
Reviewed on an **inline confirm card in the chat** — the single piece of UX it shares with the
[[Agent]]'s propose-then-accept flow — never routed to the [[Inbox]].
_Avoid_: [[Plan proposal]] (the Agent's client-side plan delta — a different capability and commit
path), draft (an [[Inbox]] brain-dump draft, committed client-side via `CreateItem`; an Assistant
proposal applies server-side), [[Command]] (the offline-first client write seam — a proposal bypasses
it: the server writes).

**Conversation** *(client; Assistant)*:
One persisted [[Assistant]] chat thread — a server-assigned id plus its ordered messages (the person's
prompts, the assistant's streamed replies, and any [[Assistant proposal]]s and their outcomes). The
client **caches each Conversation locally as it streams**, so past chats are readable offline and
resumable; the server keeps its own copy ~30 days (`GET …/assistant/conversations` lists recent ids,
most-recent first; `GET …/assistant/conversations/{id}` returns one Conversation's full message log).
The iOS client treats its **local cache as the source of truth** for readable history and surfaces a
**multi-conversation switcher**; opening a Conversation is **local-truth + server backfill** (the cache
renders immediately, then the server copy merges in any missing messages — e.g. a turn started on web).
Online-only to *extend* (a new turn needs a live connection); readable offline.
_Avoid_: transcript (reserved for a [[Dictation]] result — the server/wire calls a Conversation's log a
"transcript", but in this client that word is already taken), thread, session, history (that is the
*log* of messages, not the thread itself).

### Agent

**Agent** *(client)*:
The client-side capability that turns the person's own context into **proposals** — today two
propose-only services, the [[Extractor]] and the producer of the [[Plan proposal]], running against
the configured [[Inference engine]], with read-only [[Reference lookup]] tools when it must resolve
existing [[Item]]s. It holds no write access and **never commits anything**: the person accepts a
proposal, and that acceptance commits through the ordinary write path. Reserved to grow into the
Command-issuing agent the [[Command registry]] anticipates.
_Avoid_: copilot, bot, "the AI" (name the capability or the specific service); the [[Assistant]] (the
backend-mediated conversational AI — a genuinely distinct capability, no longer just a word to avoid).

**Extractor** *(client)*:
The propose-only [[Agent]] service that turns a [[Brain dump]] [[Transcript]] into **draft
[[Task]]s** — titles, decompositions (parent/child), sequences (next-task chains), `completeBy`, and
`desire`/`productive`. Drafts may use opaque local ids in the same structural slots as Item ids so
parent/child and sequence refs have something stable to point at before acceptance. Its output is
always drafts for the person's review, never committed work.
_Avoid_: parser, brain-dump AI, voice commands (routing speech to Commands — a non-goal).

**Plan proposal** *(client)*:
The [[Agent]]'s proposed **delta against the day's seeded plan** — backlog [[Task]]s worth adding
(ranked by the derived [[Priority]]), removals when the day overflows, and an ordering — reviewed on
the Plan [[Destination]]. Accepted entries commit through the ordinary plan verbs; the proposal is
**never auto-applied**, and the server-seeded plan remains the substrate it amends.
_Avoid_: generated plan, AI plan (the Agent *curates* the seeded day; it does not author it).

**Proposal recovery** *(client)*:
When a proposal references an [[Item anchor]] that can no longer be resolved, the client preserves the
person's draft/proposal and asks them to repair or discard the affected part. Stale references are a
review problem, not a reason to silently trash generated work; a missing parent can be resolved by
saving the draft at the root/top level, while a missing child relation can be removed.
_Avoid_: silently dropping drafts, auto-committing a guessed replacement.

**Salvage draft** *(client)*:
The single fallback [[Brain dump]] draft the client creates when a recording **can't** be turned into task
drafts — no on-device [[Inference engine]] is available, transcription failed, or the [[Extractor]] yielded
nothing — so a spoken take is **never lost**. It carries the source recording and a short note of *why*,
lands in the [[Inbox]] like any draft for the person to finish by hand, and on accept becomes a [[Task]]
with the audio attached. A salvage is the one case that keeps the recording even when the keep-recordings
preference is off (the audio is the only artifact left). One per take; every platform behaves the same —
the embodiment of "never waste a recording."
_Avoid_: error toast (a salvage is a recoverable artifact, not a dismissed failure), dropped/discarded
recording, [[Proposal recovery]] (that repairs a *broken reference* inside an existing proposal; a salvage
stands in for a take that produced *no* proposal at all).

**Inference engine** *(client)*:
The backend the [[Agent]] runs against, chosen from a **catalog** of two-or-more options on the
Settings [[Destination]]: **Off** (the Agent stands down — no inference of any kind), one or more
**on-device engines** (available to **everyone**, ungated — and now the **default**: where the device can
run one it is selected out of the box, since on-device inference is private, free, and never leaves the
device; onboarding later confirms or disables it), and **Deferno relay** cloud engine(s) (Deferno-operated,
gated by per-[[Account]] **entitlement**). The choice is an [[App setting]] (device-local, like the
speech-engine choice). On-device inference is ungated and **on by default**; an off-device (relay) engine is
**explicit opt-in** — *selecting* it is the consent — and **never silent** (the privacy invariant: nothing
off-device happens without the person choosing it — ADR-0027, amended). An engine *hosts* a **model** (the relay exposes the models an
entitlement covers); picking a specific model is a sub-choice **within** the chosen engine, and gating is
**per-origin** (every cloud model needs entitlement; every on-device model is free), never per-model.
Distinct from the [[Dictation]] speech engine — speech recognition never has a cloud tier.
_Avoid_: model (an engine *hosts* a model — a sub-choice, not the engine), provider, speech engine (a
different catalog).

**Reference lookup** *(client)*:
Read-only [[Agent]] tools that resolve the person's spoken/written references to existing [[Item]]s
through deterministic function calls and **local** search indexes. They may return Item anchors for
proposals to reference; they never mutate data, never query the server, and never bypass the person's
review/acceptance step.
_Avoid_: write tool, server search, autonomous write agent, commit tool.

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
recognition. (A [[Brain dump]]'s *source recording* may be **retained on-device** as a Task attachment when
the person opts in — see that entry — but recognition itself, and the audio leaving the device, stay absolute.)
_Avoid_: recording (a [[Dictation]]'s audio is transient, not kept — distinct from a [[Brain dump]]'s
optionally-retained recording), recognition result, dictation (that is the *act*).

**Preflight** *(client, desktop)*:
The **prompting resolution of [[Dictation]]'s two OS permission gates** — speech recognition first,
then the microphone, the same order the [[Sidecar helper]]'s own first-use prompts fire in (specified
in the [[Sidecar protocol]]) — run by the speech port before the Helper's mic engages. An undetermined
gate fires the real OS prompt; a settled denial blocks and names **which** gate; an unknown state stays
clear so the first real subscribe can still prompt. Owned by the port: OS permission vocabulary (TCC
states) never crosses it. Distinct from **readiness**, the introspect-only signal that never prompts.
_Avoid_: permission check (ambiguous between this and readiness introspection), TCC (the macOS
implementation detail behind the seam).

**Brain dump** *(client)*:
Free-form spoken capture whose [[Transcript]] the [[Extractor]] turns into **several draft
[[Task]]s** the person reviews before anything is created. Unlike
[[Dictation]] (which fills one field and infers nothing), a brain dump is inference-heavy and relaxes
ADR-0015's "kind is explicit, never inferred." The drafts are exactly that: *drafts*, never
auto-committed. A take that can't be turned into drafts is never lost — it becomes a [[Salvage draft]] instead. The extractor **never runs silently**: an off-device model only by the person's
**explicit opt-in**, and when a local engine is available it is preferred by default (the audio
itself never leaves the device — that boundary is [[Transcript]]'s and stays absolute). The source
**recording** is transient by default, but the person can opt to **keep it on-device** (a device-local
"keep brain-dump recordings" [[App setting]], default on, #211): on accept it is retained as a Task
attachment via the on-device storage provider (#210), discarded once every draft from a recording is
triaged. Keeping it never weakens the boundary above — it is storage of the person's own content, not
recognition, so the audio still never leaves the device unless the person later chooses a cloud provider.
_Avoid_: [[Dictation]] (fills one field, no inference), voice commands (a non-goal).

### Native sidecar (desktop)

How the **desktop** client reaches OS-native capabilities the JVM can't (on-device dictation, OS
permission prompts, notifications, a menu-bar item) — a separate native process over a local socket,
not JVM→native FFI (ADR-0024/0025). The one exception is the [[Native macOS app]], which calls these
same Swift capabilities in-process (no Helper, no socket).

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

**Native macOS app** *(client, macOS)*:
The **genuinely-native SwiftUI/AppKit macOS app** (`app/macosApp`, ADR-0029) — a real Kotlin/Native
`macosArm64` `Deferno.framework` (twin of the iOS framework) linked by a SwiftUI Xcode app. **Distinct
from the Compose Desktop JVM app** (`app/desktopApp`), which stays as the cross-platform desktop target.
Because Swift can call SFSpeech/EventKit/Foundation Models directly, it runs native capabilities and
on-device inference **in-process** — reusing the macOS [[Sidecar helper]]'s Swift sources, but with no
[[Sidecar]] process or socket. The JVM-can't-FFI premise behind the Sidecar evaporates here.
_Avoid_: "the desktop app" (that is the Compose Desktop JVM app), Mac Catalyst (Kotlin/Native emits no
`macabi` slice, so a Catalyst app can't link the iOS framework — this is a real macOS target).

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
Covers OS/app permissions, device notification preferences, the [[Dictation]] **speech-engine
choice**, and the [[Agent]]'s **inference-engine choice**. Lives in a device-local store, *not* in
[[User setting]]s, because the thing it configures is a
device capability (an engine available on one device may be absent on another). Surfaced on the same
**Settings** [[Destination]] alongside [[User setting]]s.
_Avoid_: [[User setting]] (the synced, per-Account kind), system/OS setting (those live in the platform
Settings app, not Deferno).

### Storage (on-device)

**On-device attachment** *(client)*:
An attachment whose **bytes physically live on this device** — e.g. a kept [[Brain dump]] recording
retained as a [[Task]] attachment (the offline-first #210/#211 path). The **only** kind of attachment
that consumes device storage: a synced/remote attachment is **backend-hosted**, and the client holds
only a signed `url` for it — zero device bytes, fetched on demand, never cached. Scoped to the
[[Active Account]]: each Account's on-device attachments live separately. What the Settings **Storage**
section measures.
_Avoid_: counting a backend-hosted attachment as device storage; "cache" (these are the person's own
retained bytes, not re-fetchable — removing them is data loss, not eviction).

**Storage provider** *(client; foundation seam)*:
The device-local [[App setting]] naming **where an [[On-device attachment]]'s bytes are meant to
live** — *On-device* (the default, and today the only functional one), *Deferno-hosted*, *Dropbox*,
*Google Drive*. Today a **label, not a behavior**: the choice is recorded and shown, but bytes are
stored on-device regardless of the selection. The non-default providers are roadmap; selecting one
must never imply the bytes leave the device before routing actually exists.
_Avoid_: presenting a non-On-device provider as functional today; the [[Inference engine]] or
speech-engine catalogs (different [[App setting]]s).

### Data portability (export / import)

The person's data is theirs to take out and bring back. Two distinct ways to take it out (an
[[On-device export]] and a [[Full extract]]) both produce the same [[Backup file]], and one way to
bring it back ([[Import]]).

**Backup file** *(client)*:
The portable artifact of a person's own data — a **zip** whose `items.json` is the REST response
envelope (`{ version, data }`, the *same* DTO shapes the API's read endpoints emit) and whose
`attachments/` folder holds attachment bytes. It is **compatible with the web API by construction**:
`items.json` *is* the API's own JSON, carrying the same envelope `version` the client gates reads on
(ADR-0005), so a file an [[On-device export]] writes, a [[Full extract]] writes, and the web app reads
are one format. **Full-fidelity-capable**: the format carries items (Task/Habit/Chore/Event) with
their comments, history, and attachments — but how much is actually filled depends on the export mode
([[On-device export]] writes only what the device holds; a [[Full extract]] writes everything).
_Avoid_: dump, CSV (the format is the API envelope, not a spreadsheet), archive (overloaded with the
macOS Helper's audio archive), "the export" (that is the *act* — this is the file it produces).

**On-device export** *(client)*:
Building a [[Backup file]] **locally and entirely offline** from the local source-of-truth DB —
**only what the device actually holds**: items (a never-opened, un-hydrated item exports without its
`description`) plus [[On-device attachment]] bytes. It **omits** comments and item history (never
cached locally) and references no backend-hosted attachment (only a [[Full extract]] can include
those). Immediate; handed to the OS share sheet. The offline-first half of export — always works with
no network, at the cost of being a partial snapshot.
_Avoid_: [[Full extract]] (the online, server-built counterpart), "backup" unqualified (name which of
the two export modes).

**Full extract** *(client; server-mediated)*:
The **online** counterpart of [[On-device export]] — a **server-side export job** the client merely
*requests*, which assembles the complete [[Backup file]] including **backend-hosted attachment bytes**,
stores it in object storage, and **emails the person a time-limited download link** (~30 days). The
client triggers it and tells the person to check their email; the heavy gather/package/host happens
server-side (the server mediates the external work, ADR-0038). Chosen at export time when the person
wants bytes the device doesn't hold.
_Avoid_: [[On-device export]] (the offline, device-built counterpart), sync (a [[Full extract]] is a
one-shot archive, not continuous replication).

**Import** *(client; a restore, not a copy)*:
Reading a [[Backup file]] and re-creating its items **with their original ids** through the
offline-first create path ([[Command]] + outbox), so it is **idempotent**: re-importing the same file
changes nothing because the backend dedupes on the client-supplied id (ADR-0034). A genuine
**restore/merge** — an incoming item that collides with an existing one that differs is reconciled
last-writer-wins (the existing reconcile behaviour), not duplicated. Restores items and their embedded
[[On-device attachment]] bytes (re-created as local attachments, never re-uploaded); **comment** restore
arrives with the [[Full extract]] (no [[On-device export]] file carries comments). Item **history** is
export-only and is **not** restored: the backend owns the audit log and writes its own
"created/imported" entry.
_Avoid_: copy/duplicate (those would mint new ids — [[Import]] preserves them), merge as a diff/review
(it does not prompt per conflict; it last-writer-wins), [[ExternalProvenance]]/"imported item" (the
backend's term for a row synced *from* Google Tasks/Todoist/etc. — a different concept).

# v1 navigation surface: buildable Destinations now, backend-blocked surfaces deferred

**Context.** ADR-0007 fixes the three navigation tiers and ADR-0013 the two-state [[Shell]] + the
multiple-back-stack Destination graph. Neither pins the **concrete v1 Destination set**. Android
wireframes (2026-06-07) propose a richer surface than the backend supports, so this ADR records the
v1 set *and why it is narrower than the wireframes* — a future reader seeing the wireframes will
otherwise wonder where Workspaces, Groups, Permissions, and the mood check-in went.

Grounding against the backend OpenAPI (`version: 0.1`): **no Workspace API and no org-listing
endpoint** (a user has exactly one knowable [[Org]] via `personal_org_id`); **`group_id` is reserved
and ignored** — "future Groups subsystem (Spec 2)"; **no permissions / members / roles API**
(`UserRole` is only system-wide `user|admin`); **no daily-mood endpoint** (mood is captured *only*
per-task as `mood_start`/`mood_finish`). The wireframes' Scope-&-people and daily-mood surfaces are
therefore UI ahead of backend.

**Decision.**

- **v1 primary nav** (Material 3 `NavigationSuiteScaffold`, size-class adaptive): **Plan (home) ·
  Calendar · Tasks · More**. The v1 tier-1 Destinations are **Plan, Calendar, Tasks, Profile,
  Settings** — peers, each with its own back stack (ADR-0013).
- **"More" is a compact-only overflow launcher, not a Destination.** On phone it opens a hub to the
  secondary Destinations (Profile, Settings); on rail/drawer (medium/expanded) those are listed
  directly and "More" disappears. The secondary screens stay tier-1 peers, never tier-3 children of a
  "More" Destination (which would forfeit per-Destination state preservation).
- **Search and New are shell-level overlay routes**, launched from any app bar (⌕) / FAB — one
  implementation each, pushed and popped over the current context, *not* Destinations. **New** uses
  an **explicit Task/Habit/Chore/Event kind picker** (not field-inference — predictability wins, per
  design-principle #5) and is **online-only** (ADR-0016).
- **No separate "All Tasks" Destination:** it is Tasks scoped to the Personal [[Workspace]] — the lone
  v1 Org.
- **Settings is a tier-3 drill-down** (ADR-0007 tier 3) that **renders all wireframe categories**,
  with gentle coming-soon stubs for the unbacked ones (**Security & 2FA** → Zitadel-hosted;
  **Integrations** → no API). This is a deliberate, scoped exception to "no dead ends": a settings
  list missing obvious rows reads as broken, whereas a whole stubbed Destination does not.
- **Deferred — kept in `CONTEXT.md` but not built in v1**, each with a known unblock:
  - **Workspaces · [[Group]]s · Permissions · the inline workspace-selector** — blocked on the
    backend Groups subsystem ("Spec 2": Workspace, org-listing, and permissions/membership APIs).
  - **All mood capture** (daily arrival check-in *and* per-task) — the daily check-in has no endpoint
    (filed **Kyle-Falconer/Deferno#308**); deferred to a holistic mood pass (data-model fields
    retained).
  - **Agenda · [[Dashboard]]** — no current need; Calendar subsumes the Agenda role.
- The Destination graph is built so each deferred surface drops in later as a peer Destination or
  overlay route with no structural change.
- **The Auth shell is out of scope here** (parked; ADR-0012's browser-PAT-bootstrap stands, dev-paste
  is the live path).

**Consequences.** v1 nav is intentionally narrower than the wireframes; the gap is backend-gated,
documented per-surface, and reversible-forward (add the Destination when its API lands). Calendar is
the largest net-new surface but reuses the Habit/Chore/Event types the create kind-picker already
requires, so its marginal cost is the month/day UI + occurrence queries, not new modelling.

**Rejected.**

- **Shipping Workspaces/Groups/Permissions as coming-soon Destinations**, or forward-building a
  client-only scope model over the single known Org — the former dead-ends whole Destinations; the
  latter invents scope semantics the backend Spec 2 may contradict.
- **Auto-inferring item kind** in the create form (the wireframe's "looks like a Task · tap to
  change") — rejected for design-principle #5 (predictable, minimal surprise); explicit picker
  instead.
- **"More" as a real Destination** owning the secondary screens as tier-3 children — loses the
  per-Destination state preservation ADR-0013 guarantees.
- **A daily mood check-in built client-local/unsynced** — invents unsynced semantics; lost across
  devices.

**Amendment (Inbox Destination — brain-dump async redesign, Stage 3).** The frozen v1 set gains a
sixth Destination: **Inbox** (`NavSlot.Secondary`, ordered first among the secondaries —
`Plan · Calendar · Tasks · Inbox · Profile · Settings`). It is the triage queue for the **persisted
draft Tasks** the [[Extractor]] produces from a [[Brain dump]] (CONTEXT.md → **Inbox**).

*Why it earns a place in a deliberately frozen set.* The brain-dump async redesign decoupled capture
from extraction — a recording is transcribed and its drafts extracted in the **background**
(WorkManager), so the drafts now **outlive their capture session** and are reviewed minutes or days
later. ADR-0027's Amendment (#150) put the accept/dismiss review *inline* in the `OverlayRoute.BrainDump`
overlay; that overlay is gone by the time background extraction finishes, so the review needs a
**durable, re-findable home**. A Destination is state-preserving with its own back stack (ADR-0013 /
ADR-0007) — the person can leave mid-triage and return — and its drafts are individually dismissable,
so pending work never blocks anything else. A transient overlay is the wrong shape for durable,
accumulating state; bolting a persistent re-entry affordance onto an overlay would just be a
Destination in disguise.

*Decision details.*

- **`NavSlot.Secondary`, first among the secondaries.** Not a daily navigation peer like Plan/Calendar/
  Tasks, and empty much of the time, so it does not earn permanent compact bottom-bar real estate; it
  surfaces via the Stage-2 completion notification (deep-link) and is listed directly on rail/drawer.
- **Always present, never hidden-when-empty,** with a nav badge that reads **"empty"** or **[n]** — the
  surface always declares whether there is anything to triage. A functional empty inbox is **not** an
  ADR-0015 "dead end" (an inbox with no mail, not a coming-soon stub).
- **Accept** commits a draft as a real [[Task]] through the ordinary **online** create path (ADR-0016),
  inheriting v1's create-online-only asymmetry: offline accept **preserves** the draft and shows
  "reconnect to save" rather than enqueuing or dropping it. **Dismiss** drops the draft unaccepted.
  Accept stays **propose-only** (ADR-0027) — the person's acceptance is what commits.
- **Retaining the recording** as a Task attachment is **out of scope here** (tracked: #211, depending on
  pluggable offline-first attachment storage #210); Stage 3's accept seam reserves the hook.

*Consequences.* The Destination registry (`app/shell/.../Destination.kt`), `MainShellComponent`, the
shared `ShellChrome`, and the Main shell View each gain the Inbox entry — the four touch points #176
anticipates collapsing. ADR-0027's Amendment (#150) is hereby updated: the brain-dump review is no
longer an inline overlay list but the persistent **Inbox** Destination.

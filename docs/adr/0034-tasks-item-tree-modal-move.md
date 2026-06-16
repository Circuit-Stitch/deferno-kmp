# Tasks Destination renders the Item decomposition tree; modal button-based Move over `/items`

**Context.** The Tasks [[Destination]] today is a flat `LazyColumn` ordered by `sequence`, with parents
and children intermixed at one indent level — the "flattened to a single layer" the user sees. A
*separate* one-level drill pane (`TaskTreeComponent`/`TaskTreeScreen`, the ADR-0007 tier-2 "co-resident,
not a stack" shape) shows a root plus its **direct children only**, drilling into a child's own detail on
tap — a deliberate "decompose to defeat paralysis" posture (design-principles #1/#2). Issue #31 (P5) asks
for drag-to-reorder/reparent across the tree.

Two blockers surfaced when we walked the design:

1. **No reparent write seam.** `ReorderPlan` exists (for the [[Plan]]), but reparent/move was explicitly
   deferred (`core/domain/.../command/Command.kt:44`): "moving a Task under a new parent has no write seam
   yet … it joins when the seam does." The backend has since added `POST /items/{id}/move`
   (`{new_parent_id, position}`; `null` parent detaches to root; server-side cycle prevention → 400).
2. **The done-visibility window can't be honored on the legacy task sync.** The synced
   [[Done-visibility window]] [[User setting]] hides terminal non-recurring [[Item]]s after a grace period.
   The client syncs via the legacy cold `GET /tasks`, which neither applies the window nor returns
   `finished_at` (summary omits it) — so the flat list *already* shows long-done tasks forever, and a tree
   would inherit that. Only `GET /items` applies the window **server-side** (cold snapshot) and carries
   `finished_at`; it returns all four kinds polymorphically plus computed `descendant_done`/
   `descendant_total` for collapsed-node progress.

A neurodivergent-first app (design-principles) also forbids treating drag-and-drop as the *only* path:
keyboard operability and screen-reader semantics are v1 acceptance criteria, and on web a row-body tap
target (variable-length titles) plus drag have caused accidental detail-opens and scroll-vs-drag
collisions on mobile.

**Decision.** Eight decisions. This **amends ADR-0007** (the tier-2 "one-level drill" shape no longer
holds for Tasks) and **supersedes the `Command.kt:44` reparent deferral**.

1. **Tasks Destination = the [[Item tree]].** One nested, collapsible tree spanning **all [[Item kind]]s
   equally** (a [[Habit]] may parent a [[Task]], an [[Event]] may sit under a [[Chore]]) — the complete
   catalog of work Items. The [[Plan]] stays a flat, calm "today" list (principle #1: the home Destination
   never opens into the whole backlog). The one-level drill pane is **subsumed** — its job (see a node's
   children) is now done inline by expand.

2. **Sync migrates `GET /tasks` → `GET /items` cold snapshot.** The server applies the window; the client
   mirrors the windowed set with **no client-side window math**; long-aged terminal items simply fall out
   of the cache on refresh. The local store generalizes from a **Task** store to an **Item** store
   (offline-first, ADR-0001). The `?since=` delta machine (cursor + tombstones) stays **deferred (#182)** —
   not needed to honor the window.

3. **Node set = the windowed snapshot.** Recurring kinds (Habit/Chore/recurring Event) never age out; only
   terminal Tasks and past one-off Events do. A child whose parent is absent from the visible set (e.g.
   aged out) is an **orphan → rendered at root** (it cannot nest under an absent parent). Terminal items
   are visually **de-emphasized**.

4. **Fold state = a device-local [[App setting]].** Stored as **explicit overrides** against a default that
   auto-collapses anything **deeper than depth 2**, keyed by item id, in one store every tree surface
   consults (the Tasks tree and the detail subtask outline share it). Collapsed rows show a
   `descendant_done`/`descendant_total` badge. Device-local, not synced — fold memory is a per-device view
   convenience; backend `UserSettings` is untouched.

5. **Move is one capability, three input skins.** A single `Move(item → newParent | null, position)`
   command (the reparent half `Command.kt:44` deferred) funnels to `POST /items/{id}/move`; `position` is an
   *insertion index* (the server reassigns `sequence`). Drag, the menu, and the keyboard are interchangeable
   triggers that compute those two arguments. **Accessibility is the design spine, built first** — the menu
   and keyboard paths are the capability; any direct manipulation rides on top. Moves apply optimistically
   through the outbox; a server 400 (cycle) reverts; the cold-snapshot refresh reconciles any order drift.

6. **No literal drag in v1.** Long-press → command menu → **Move** → a **modal move mode**: the selected
   item lifts/highlights, the rest of the list goes calm, and a contextual control offers **↑ ↓** (reorder
   among siblings) and **‹ ›** (outdent / indent), **live per press** (each is an immediate, independently
   undoable Move), with **Done** to exit. This is *also* the accessible path — no separate
   drag-for-sighted / buttons-for-a11y split — and it removes drag from the default gesture set, so
   scrolling can never masquerade as a tap or a move. Literal drag inside move mode is a deferred
   enhancement. Arbitrary-parent jumps use a separate **"Move to…"** picker.

7. **Per-row interaction map + kind-aware menu.** Leading `▾/▸` *and* a body tap = expand/collapse
   (childless leaf body is inert); a trailing `›` is the **only** "open detail" affordance (a fixed target,
   immune to title length and scroll). Long-press opens the command menu, which mirrors the web submenu —
   Open · Add subtask · Move · Move to… · Pin · Add to today's plan — **plus** Move/Move to… (web's move is
   drag, which fails on mobile). The **status block is kind-aware**: Start Working / Mark Done / Drop are
   Task ([[Working state]]) verbs; other kinds get their own. "Pin to Sidebar" becomes just **Pin** (no
   "Sidebar" exists in the native client).

8. **Undo: snackbar + shake.** A top-anchored "Moved · Undo" snackbar (below the chrome, out of thumb
   reach) on reparent/indent/outdent moves, plus **shake-to-undo** (a confirm prompt — "Undo
   [operation]?"). Shake is a device-local toggle (**default on**), never the sole path, and the confirm
   prompt is the accidental-fire safety (reconciling a motion gesture with reduced-motion / involuntary-
   movement users). Undo is **Move-only, single-level** (a generic `lastUndoable` hook so it can grow); a
   shake in an unsupported context emits a tracking event.

**Consequences.** The local data layer generalizes Task→Item (the tree must persist Habits/Chores/Events,
not just render them). `TaskTreeComponent`/`TaskTreeScreen` are removed or repurposed. The reparent `Move`
command finally lands. This is an **epic**, not one issue — roughly: (a) `/items` cold-snapshot sync +
Item store; (b) Item-tree rendering + fold store; (c) `Move` command + modal move mode; (d) undo (snackbar
+ shake) + tracking event; (e) menu wiring (kind-aware). **Filed as separate fast-follows:** literal drag
in move mode · multi-select · general last-action undo · an analytics/telemetry seam · decomposition ops
(Split/Fold/Merge/Convert) in the menu · the iOS/macOS SwiftUI tree · multi-window / resize-without-state
(#31's other half, ADR-0008).

**Rejected.**

- **A full nested tree on the Plan** — overwhelm; the Plan is the calm home (principle #1). Plan stays flat
  and keeps `ReorderPlan`.
- **Arbitrary-target drag** (insertion-line whose level tracks the finger, or drop-onto-vs-between) — fiddly
  hit-testing, error-prone on touch, and it gives drag a capability the keyboard path must mirror
  separately. Move mode's relative ↑↓‹› plus "Move to…" covers it predictably.
- **The `?since=` delta sync machine now** — a separate, larger build (#182); the cold windowed snapshot
  honors the window without it.
- **A general last-action undo subsystem** — a cross-cutting feature touching every Command; v1 is Move-only
  with a hook shaped to grow.
- **Literal drag and multi-select in v1** — deferred, not dropped.
- **Re-deriving the window client-side** — the legacy `/tasks` path lacks `finished_at`; the server-windowed
  `/items` snapshot is the source of truth instead.

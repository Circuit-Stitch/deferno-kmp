# Detached, navigable per-task detail windows on macOS

**Context.** The native macOS app (ADR-0029) renders the shared Compose-free shell (ADR-0017) in a
**single** `Window` — a deliberate posture from ADR-0026/#189, where the OAuth redirect re-entering via
the custom scheme *must* land on the live shell so the in-flight sign-in's inbox receives it, and a
`WindowGroup` would have spawned a stray second window. The Tasks/Plan detail (#195) is an enriched
`TaskDetailComponent` — editable PROPERTIES (Due/Labels), the nested-subtask outline with drill, the
working-state editor — rendered today only as an **inline pane**. The shell's Plan tier-3 stack (#51)
already drills that detail in-place (`PlanChild`: Dashboard ↔ `Detail(task)`, push on a Plan/subtask
tap, pop on Back) — a stack-of-details that never leaves the one window.

#196 wants a task openable into **its own window**: a self-contained workspace rooted at that task,
where subtasks render inline and a chevron pushes the child's detail (Back walks up), coexisting with
(not replacing) the main window's inline pane. All windows should share the one in-process DI graph so
an edit in any window syncs to the others for free.

**Decision.** Four decisions; this **amends ADR-0026/#189** (single-window) and **ADR-0029**.

1. **A second SwiftUI scene, not a second shell.** Add a value-based
   `WindowGroup(id: "task-detail", for: String.self)` **alongside** the existing `Window("main")`. The
   scene value is the **raw task-id String** (Kotlin `TaskId` is a value class; `WindowGroup(for:)`
   needs `Codable + Hashable`, and a String is the simplest carrier — `TaskId` is reconstructed inside
   the Kotlin opener). A task row's double-click / context-menu "Open in New Window" fires
   `openWindow(id:value:)`. The `main` Window remains the **sole** owner of `onOpenURL` + sign-in — the
   detail scene never handles auth (the #189 invariant holds). Same-task **dedupe is native**: a
   value-based group brings the existing window to the front rather than duplicating it.

2. **A per-window Decompose stack root.** A new shared-core component
   `TaskDetailStackComponent` (`feature/tasks`, commonMain) is the Plan tier-3 stack **minus the
   Dashboard base**: a `ChildStack<*, TaskDetailComponent>` seeded at the root `TaskId`, drilling a
   subtask pushes `Detail(childId)`, Back pops. It reuses the enriched `DefaultTaskDetailComponent`
   (#195) unchanged and mirrors `MainShellComponent.onPlanDetailOutput` exactly (`SubtaskSelected →
   push`, `Closed → pop`, `TreeRequested`/`AddToPlanRequested` ignored — no tree screen or add-to-plan
   in a window). It is unit-tested (push/pop/back-at-root) — the Kover-gated logic.

3. **Per-window lifecycle from the shared graph.** Each window mints its own Essenty
   `LifecycleRegistry` + `DefaultComponentContext` (`resume()` at open, `destroy()` when SwiftUI tears
   the scene down — mirrors `DefernoRoot`), owned by a Swift `TaskDetailWindowModel` whose `deinit`
   calls `destroy()`. No leaks across open/close cycles.

4. **Reuse the LIVE account session — do NOT mint a fresh `AccountComponent`.** This is the one
   deliberate **deviation** from #196's stated approach (which said "Repos/editor via
   `createAccountComponent(appComponent, activeAccount)`"). `createAccountComponent` is `@CreateComponent`
   — it mints a **fresh** `AccountComponent` per call, and `taskRepository` is `@SingleIn(AccountScope)`,
   so each call gets its **own SQLite driver**. SQLDelight query notifications are **per-driver**, so a
   write through window B's driver would never re-emit into window A's `observeTasks()` flow — the
   "edit in one window updates the other live, and vice-versa" acceptance criterion would **fail**, and
   we'd open a second SQLCipher connection to the same encrypted file. Instead, the macOS opener reads
   the **live** `AccountSession` the main shell already holds, newly exposed as
   `RootComponent.activeAccountSession` (`null` on the Auth shell → "detail windows unavailable when
   signed out"). One driver, one query-notification bus → cross-window live sync is free, and there's
   no second DB connection. This is both correct *and* lazier than the issue's text.

**Consequences.**

- `RootComponent` gains one read-only property (`activeAccountSession`); the shell is otherwise
  untouched. The macOS bridge gains a `TaskDetailWindowRoot` handle + `DetailStackBridge` (twin of
  `PlanStackBridge`) + an `openTaskDetailWindow(root, idValue)` factory that returns `null` when signed
  out or given a blank id, so the Swift opener simply does nothing in those cases.
- **Account isolation:** the window closes itself on sign-out / account switch — the root swaps its
  active child (→ Auth, or a re-keyed Main for the new account), which the window observes and treats as
  "my captured session is no longer active", so another account's task is never left on screen.
- `TaskDetailView` gained one optional `hidesBackControl` flag (default `false`, so inline/Plan callers
  are unchanged): the window's **root** entry (depth 1) hides the header Back — there's nothing to pop
  and the OS window chrome closes it; a drilled entry keeps the Back, which pops via the detail's
  existing `Closed` output. The window renders the active stack entry + Back rather than a full
  `NavigationStack` — enough for inline subtasks / push / pop / arbitrary depth.
- **Deployment target stays 14.0**; `restorationBehavior(.disabled)` (macOS 15+) is *not* used — instead
  a restored/stale window degrades gracefully (a missing task shows the detail's "Task not found" empty
  state; a signed-out restore closes the window).
- `createSubtask` is left at its no-op default — add-subtask inside a window is #197 (comments/activity
  too). The window is read + working-state/PROPERTIES edits only, all already offline-first through the
  reused session's command executor.

# Top-bar create affordances stay overlay/navigation intents, not Command-registry commands

**Context.** ADR-0007 introduced a **Command registry** — `core/domain/.../command/` (`Command`,
`CommandKind`, `CommandExecutor`, `CommandBindings`, `WorkingStateCommands`), DI-wired in the
`AccountComponent` across Android/JVM/iOS. `CommandKind` is explicitly a "**stable binding token**" that
"an AI tool-schema, an OS-intent registry (Android App Actions / iOS App Intents), telemetry, and a
command palette all key on" — built so one catalog binds **many** surfaces without per-surface
re-derivation. An architecture review (`/improve-codebase-architecture`, 2026-06-15) noted the registry
exists but the **top bar was not wired to it**, and asked whether the top-bar actions should route
through `CommandKind`. After ADR-0031, those actions live in `ChromeSpec.actions`
(`ChromeActionKind`: Refresh · BrainDump · New), computed in `MainShellComponent.rootChrome(...)`.

**Decision.** The top-bar create affordances **stay overlay/navigation intents**, *not* direct
Command-registry commands, until a **second binding surface** exists. The three actions are not
registry-shaped:

- **New** → `openOverlay(OverlayRoute.New())` — opens the create *form*; `CommandKind.CreateItem` only
  fires when that form **submits** (already routed through `CommandExecutor` there). The top-bar button
  is "open the form", a navigation intent, not "create an item".
- **Brain dump** → `openOverlay(OverlayRoute.BrainDump)` — opens the recorder overlay (a navigation
  intent).
- **Refresh** → `component.onRefresh()` / `list.onRefresh()` — a per-screen data reload with no catalog
  identity.

None is a parameterised mutation of a known operand, which is what the registry binds. The review
flagged this **Speculative**: it would be "one adapter, not two". The registry's value is **fan-out** —
one catalog, many surfaces — so the first surface that routes a *navigation* intent through it pays the
indirection cost (a `ChromeActionKind` → `CommandId` → executor hop) and buys nothing, because there is
no second surface reading the same binding yet.

**Consequences.** No code changes; this ADR records the boundary so future architecture reviews do not
re-suggest the wiring. The trigger to revisit is concrete: a **second binding modality** that needs the
*same* create/capture affordances — iOS **App Intents**/Siri, a desktop **Cmd+K command palette**, or
macOS **menu-bar** items (ADR-0029). At that point the duplication the registry exists to kill becomes
real, and routing New/Brain dump through a `CommandKind` (likely an `OpenCreate` / `OpenBrainDump`
*navigation* kind, distinct from the existing data-mutation `CreateItem`) earns its keep. Until then the
registry stays bound to the **mutations** it already serves (Task lifecycle, plan membership, settings,
occurrence, and `CreateItem` on form submit), and the chrome stays bound to **navigation**.

**Rejected.**

- **Wire `ChromeAction` through `CommandKind` now** — premature. A binding token with one consumer is
  indirection without fan-out; it adds a hop and a new navigation-flavoured kind to maintain before any
  second surface reads it. ADR-0031's `ChromeActionKind` is already the right-sized seam for one
  surface.
- **Route only `New` through the existing `CreateItem`** — category error: the top-bar New opens a
  *form*, it does not create. `CreateItem` fires on submit (with the entered operands) and is already
  wired there; binding the button to it would either skip the form or double-fire.

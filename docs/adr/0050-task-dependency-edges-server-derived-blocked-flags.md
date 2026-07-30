# Task dependency edges are client-writable; the Blocked and blocker flags are server-derived

**Status.** Accepted (#291).

**Date.** 2026-06-26 (shipped across #289–#292; recorded retroactively 2026-07-29)

**Issue.** #289 (wire and model shape) · #291 (the edge write and picker) · #292 (the blocked
reading on search, plan and detail). Related: #290 (ready-only filter and blocker badge).

**Context.** Two relations in this client already read as "dependencies": a **sequence**
(`nextTaskId` — "first X, then Y") and a **decomposition** (`parentId` — "Y is part of Z").
Neither expresses "I cannot start this until that is done", so people faked that with labels and
notes.

The backend then added a real dependency edge. A [[Task]] carries an ordered **`blocked_by`**
array of refs, and the server derives two booleans per [[Item]] from the whole edge graph:
**`blocked`** (an unresolved blocker, or a blocked ancestor) and **`is_blocker`** (this Item gates
at least one other).

Two properties of that graph decide everything below. First, **edge validity is a global
property**: an edge is illegal if it closes a cycle anywhere in the graph, or if it crosses an
[[Org]] boundary. The client holds only the [[Active Account]]'s windowed `/items` snapshot, never
the graph, so it cannot decide either question. Second, **`blocked` is not a local fact about a
row**. It inherits *down* the decomposition tree, so a row can be blocked because of an ancestor
whose own blocker the client may never have cached.

That collides with ADR-0001's write model. Every other edit is offline-first: apply
optimistically, enqueue an intent, replay later. A rejection on replay is invisible, because the
outbox has no one to tell. For a dependency edge a rejection is not an edge case but the expected
correction ("that would be circular"), and it has to reach the person who just made the edit.

The shape landed across #289 to #292 with its rationale written only into code comments and the
`CONTEXT.md` glossary, all of which cite an "ADR-0034" passage that exists in neither record
numbered 0034. This ADR is the decision those citations were reaching for.

**Decision.** The edge list is client-writable; the derived flags are read-only server truth.

- **One set-command, not add and remove verbs.**
  [`SetTaskBlockedBy(taskId, blockers)`](../../core/domain/src/commonMain/kotlin/com/circuitstitch/deferno/core/domain/command/Command.kt)
  replaces the whole ordered list, and an empty list clears every edge (the field is always
  present, never absent — the `SetTaskLabels` shape of ADR-0011). The picker composes the full
  target list because the server validates it as a set.
- **`SetTaskBlockedBy` is online-only, because the server alone validates the edge set.** A cycle
  or a cross-Org edge comes back as a `400` verdict the person must see now, and an optimistic
  offline write cannot deliver a verdict. Queuing it would turn the one outcome they need to hear
  into a silently dropped replay. So it carries `onlineOnly = true` in
  [`CommandKind`](../../core/domain/src/commonMain/kotlin/com/circuitstitch/deferno/core/domain/command/CommandKind.kt),
  the ADR-0016 `ConvertItem` posture.
- **The write path is therefore apply, PATCH, revert — not apply and enqueue.**
  [`BlockedByWriter`](../../core/data/src/commonMain/kotlin/com/circuitstitch/deferno/core/data/task/BlockedByWriter.kt)
  returns a structured verdict of `Applied`, `Offline` or `Failed(message)`, and
  [`KtorBlockedByWriter`](../../core/data/src/commonMain/kotlin/com/circuitstitch/deferno/core/data/task/KtorBlockedByWriter.kt)
  applies optimistically inside the local-store transaction, sends `PATCH tasks/{id}`, and reverts
  to the captured pre-edit row on rejection. Offline it returns `Offline` having applied and
  enqueued nothing, which the surface renders as "reconnect to save".
- **The `PATCH` response body is discarded.** The `/tasks/{id}` detail omits the derived flags, so
  upserting the response would blank the cached `blocked` and `is_blocker` on a successful write.
- **`blocked` and `isBlocker` are read-only values on the `Item` supertype and all four kinds**
  (`Task`, `Chore`, `Habit`, `Event`), populated from the wire, and nothing re-computes them. The
  optimistic apply sets a provisional `blocked = blockers.isNotEmpty()` purely to keep the
  [[Item tree]] honest for the seconds until the next `/items` reconcile, and that is explicitly
  not a derivation.
- **`blocked` inherits down the decomposition tree, and the client honors the inheritance rather
  than re-deriving it.** The ready-only filter in
  [`ItemTree`](../../feature/tasks/src/commonMain/kotlin/com/circuitstitch/deferno/feature/tasks/ItemTree.kt)
  prunes a blocked Item **and its whole subtree as a unit**, inside the flatten. Inheritance
  crosses kinds, which is why migration `9.sqm` puts the two columns on all four kind tables.
- **Both booleans default `false` when the payload omits them** — on `ItemView`'s four variants,
  on `TaskDto`, on `RecurringItemDto`, and on every model. False is the safe direction because
  `blocked` is a *suppressing* flag: the resting tree filter hides blocked rows and the read
  surfaces mute them. Defaulting true would let an older or partial payload silently hide real
  work, the worst failure for this audience, whereas defaulting false at most shows a row as
  actionable one refresh early. The migrations follow the same reasoning: the added columns are
  nullable, an existing row reads NULL as false, and the next refresh repopulates it.
- **Blocked is orthogonal to [[Working state]].** An Open or In-progress Item may also be blocked,
  so blocked is not a sixth `WorkingState`.
- **Declaring an edge is advisory.** It never moves or locks anything, and the optional "file it
  under the blocker" reparent stays a separate, explicit `MoveItem` (ADR-0049).
- **The client's own reads of the edge list are affordances, not authority.** Because the ordered
  edges round-trip the cache (`12.sqm`, a newline-joined TEXT column on `taskEntity`), the tree can
  pre-check the picker and exclude the edited row's dependent closure from the candidates, so the
  obvious cycle is unreachable. It can also name the direct dependents that a **destructive
  unblock** (Set aside or Delete on an `isBlocker` row) would release. None of that decides
  readiness.

**Considered & rejected.**

- **Route the edge write through the offline-first outbox like every other edit (ADR-0001)** — the
  natural default, and the reason this record exists. A cycle or cross-Org rejection would surface
  as a dropped replay with no one to tell, so the edit would vanish with no stated reason.
- **Pre-validate the edge set on the client, so the write could stay offline-first** — the client
  holds no full edge graph, only the Account's windowed `/items` snapshot, so it cannot decide
  cycle-freeness or Org membership. The dependent-closure exclusion in the picker narrows the
  *candidates*, which is an affordance rather than validation.
- **Re-derive `blocked` and `isBlocker` on the client from the cached edges** — both are
  graph-global. `blocked` inherits from ancestors that may not be cached, and `isBlocker` is a
  reverse index over the whole graph, so two derivations would drift and the client's would be
  wrong.
- **A sixth "Blocked" `WorkingState`** — blocked is orthogonal to lifecycle, since an In-progress
  Item can be blocked, so folding it into the state enum would destroy a real distinction and
  force a lossy transition matrix.
- **`add` and `remove` edge verbs** — the server validates the target set as a whole and the
  picker already holds the full set, so two verbs would only multiply round-trips and partial
  states.
- **Nullable flags on the wire, where absent means unknown** — every read surface would then need
  a third rendering for "unknown". A non-null default with a documented safe direction costs less
  and never hides work.

**Consequences.** **This record is the referent for the 19 in-repo citations that read "ADR-0034"
for dependency edges** — in `CONTEXT.md`, `9.sqm`, `12.sqm`, `ItemView.kt`, `TaskDto.kt`,
`RecurringItemDto.kt`, `BlockedByRef.kt`, `Item.kt`, `Task.kt`, `Chore.kt`, `Habit.kt`, `Event.kt`
and `Command.kt`. The passage they cite exists in neither record numbered 0034, so they pointed at
nothing.

**`SetTaskBlockedBy` is the second online-only command.** `grep -rn "onlineOnly = true"` over the
Kotlin sources returns exactly two sites: `CommandKind.ConvertItem` and
`CommandKind.SetTaskBlockedBy`. Neither ADR-0016 nor ADR-0034 uses the word "sole", but both
present convert as *the* one remaining online-only command ("**Convert remains online-only** under
this ADR", "**Convert stays online-only**"). Read as an exhaustive account of the exceptions to
offline-first, both are now inaccurate. Those records are left unedited under policy A0, and this
paragraph is the correction.

New substrate. Persistence gains the `blocked` and `is_blocker` columns on all four kind tables
(`9.sqm`, schema v10) plus `taskEntity.blocked_by` (`12.sqm`, schema v13). The data layer gains
the `BlockedByWriter` port, the `BlockedByResult` verdict and `KtorBlockedByWriter`. The wire
gains `SetBlockedByPayload` and `BlockedByRefDto`, condensed at the edge (ADR-0011) into the
`BlockedByRef` model. The feature layer gains the `BlockedByEditor` seam with
`BlockedByPickerState` and `BlockedByEditError`, the readiness pruning, the dependents map and the
dependent-closure helper in `ItemTree`, and the shared `BlockedChip`.

The blocked reading is consistent across surfaces: blocked **mutes but does not strike**
("blocked, not finished") in the [[Item tree]], the [[Plan]], search and Task detail, on Compose,
on SwiftUI for iOS and on macOS.

Accepted tradeoffs. The write carries the same visible offline asymmetry convert has. A verdict
can arrive after the picker closed, which is why the typed `BlockedByEditError` sits on component
state and is surfaced as a dialog. `BlockedByRef.occurrence`, which would narrow an edge to one
firing of a recurring blocker, is decoded as a tolerant pass-through and stays `null` until the
occurrence-blocker slice lands (#289). Relates to ADR-0001 (the offline-first outbox this deviates
from), ADR-0016 (the online-only posture it reuses), ADR-0022 (the migration runbook `9.sqm` and
`12.sqm` followed) and ADR-0049 (the tree the reading renders in).

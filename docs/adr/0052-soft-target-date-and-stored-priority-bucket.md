# The soft Target date and the stored Priority bucket are read on all four kinds and written on Task

**Status.** Accepted (#375).

**Date.** 2026-08-02

**Issue.** #375

**Context.** Deferno models two *peer* date concepts on every [[Item kind]], and this client
implemented one of them. The hard `complete_by` ("when it **must** be done") drives carry-forward,
the calendar DUE line and the [[Occurrence]] deadline instant. The soft `target_date` ("when I
**want** it done by") drives sorting and surfacing only. Beside them the server carries `priority`,
a deadline-independent `fire | normal | backlog` bucket defaulting to `normal`, peer to `pinned` and
orthogonal to it. All of this was settled server-side in the companion `Deferno` repo's
`docs/adr/2026-06-29-priority-model.md`, which is explicit that the two dates coexist with **no**
`target_date <= complete_by` invariant.

Both fields already ride **every** read, list rows included, and both are **PATCH-only**. They live
on the four `Update*Payload`s and on none of the `Create*Payload`s. `target_date` is typed
`Patch<DateTime<Utc>>` — a value sets, an explicit `null` clears, an omitted key leaves it alone —
while `priority` is typed `Option<Priority>` with no null form at all.

This repo asserted the opposite of half of that. `CONTEXT.md` defined [[Priority]] as *(client,
derived — never stored)*: a reading over `desire`, `productive` and `completeBy`, with the standing
instruction that "there is **no priority field** in the domain and the client must not invent one".
ADR-0027 carries the same rule as a Decision bullet. Both were accurate when written in June 2026,
and both stopped being accurate on 2026-06-29 when the backend accepted its priority model.

ADR-0051 re-cut the WHEN axis one day before this record and deliberately left this work out. It
rejected "add a Target date row in the same pass" as unbuildable rather than unwanted, and recorded
that `target_date` was "still entirely unmodelled on this client, as is `priority`". This record is
that follow-up.

The seam it lands on already exists. A per-field [[Command]] (`SetTaskDeadline`, `SetTaskLabels`,
`SetTaskPinned`, and the rest) resolves through `CommandExecutor` to a `TaskWriter` call, which
enqueues an outbox mutation carrying its own minimal PATCH body (ADR-0001).

**Decision.** Model both fields as ordinary scalars on the item models, and keep every asymmetry the
wire imposes visible in the client types rather than smoothed over:

- **[[Target date]] is a peer of the deadline, never a second one.** `targetDate` sits beside
  `completeBy` on all four kind models and is **independent** of it: no `targetDate <= completeBy`
  rule is enforced, and all four combinations are valid. It drives ranking only. It never moves the
  calendar, never carries forward, and never becomes an [[Occurrence]] deadline instant. It is
  date-granular by intent, so unlike the deadline it gets no companion time-of-day field (ADR-0051).

- **[[Priority]] is a stored enum in `core/model`, and its ranking key is ported rather than
  re-invented.** `Priority` declares `Fire`, `Normal`, `Backlog` in rank order with `Normal` as the
  default, and `prioritySortKey` composes bucket rank, the soonest of target date and deadline, the
  deadline, and the creation time. That is a case-for-case port of the server's
  `models::priority::priority_sort_key`, carried over with its unit tests, so a locally ranked list
  and an `$orderby=priority_rank` request cannot disagree. `core/model` is exported to both Apple
  frameworks, so Swift receives a real enum rather than a string. **This reverses ADR-0027's
  "derived reading, never a stored field" bullet** and the `CONTEXT.md` entry that carried the same
  rule, both of which are marked in the same commit.

- **The server's `priority_rank` is deliberately not modelled.** It exists so that an OData
  `$orderby` can sort a readable wire string by urgency, which is a server-side sorting concern. A
  client that holds the enum and the shared key has no second representation to keep honest, and an
  offline-first client (ADR-0001) has to rank its own cache anyway.

- **Reads land on all four kinds; writes land on [[Task]] only.** The DTOs, entity columns, mappers
  and domain models carry both fields for [[Task]], [[Habit]], [[Chore]] and [[Event]], because a
  cached row cannot rank itself offline without them and restricting them to Task would be the
  special case. The write path stops at `SetTaskTargetDate` and `SetTaskPriority`: `core/data` has
  no per-field PATCH seam for a recurring definition — `DefinitionWriter` is a one-method light
  switch for `DefinitionState` — and no platform has a recurring detail surface to drive one from.
  The asymmetry is deliberate, and widening it is named as follow-up work below.

- **One command with a nullable operand carries the soft date, and the bucket is never nullable.**
  The command `SetTaskTargetDate(id, Instant?)` follows the `SetTaskDeadlineTime` shape (#348)
  rather than the older `SetTaskDeadline`/`ClearTaskDeadline` split, because that shape *is* the
  `Patch<T>` wire encoding. An omitted key is a silent server-side no-op, so the clear has to emit
  an explicit JSON null (ADR-0011). The bucket command takes no null at all: "no priority" is
  `Priority.Normal`, a real value, so clearing is spelled `SetTaskPriority(Normal)`. A null there
  would be a 422, which the outbox classifies Terminal and dead-letters instead of retrying.

- **Priority is a term in ranked views only, and reorders no curated surface.** The pinned list, the
  [[Plan]]'s user-arranged order and the [[Item tree]]'s root order stay exactly as the person
  arranged them. A ranked view opts in by applying `prioritySortKey`. The bucket also stays
  orthogonal to `pinned`, which remains a curated quick-access list rather than a rank.

- **Migration 17 to 18 adds both columns nullable with no back-fill.** NULL is the correct reading
  in both cases rather than a placeholder: for `target_date` it *is* the domain value, and for
  `priority` it decodes to `Normal`, which is exactly what a row the server sent before the field
  existed means (the backend spells it `#[serde(default)]`). An un-refreshed cache therefore reads
  identically to an explicitly normal one. Contrast the 16 to 17 migration, whose `occurred_at` had
  to be back-filled because NULL sorted a row out of reach.

- **The New create surface stays out of scope.** Neither field appears on any `Create*Payload`, so a
  create cannot carry them today whatever the client sends. Both are set from item detail, on the
  shipped backend, through the ordinary PATCH path.

**Considered & rejected.**

- **Fold the soft date into `complete_by` by moving the deadline earlier.** Rejected. Carry-forward,
  the calendar DUE line and the occurrence deadline instant all read `complete_by`, so wanting a
  thing sooner would change when it is genuinely due. That is the exact conflation the server record
  rejected, and it would make "lower this item without touching its deadline" inexpressible.

- **Keep priority derived from `desire`, `productive` and `completeBy`** — the standing rule in
  `CONTEXT.md` and ADR-0027. Rejected. The derived reading and the stored field would disagree the
  moment anyone set `priority` from the webui, the MCP tools or the Assistant, and the client would
  have no way to know which one the person meant. The derived reading also cannot express a `Fire`
  item that has no deadline at all, which is the lane the server model exists to provide.

- **Model `priority_rank` and sort by the server's number.** Rejected. It is a second representation
  of a fact the client already holds, and it would let a cached row and a freshly fetched row rank
  differently. The client cannot ask the server to sort an offline cache in any case.

- **A `SetTaskTargetDate`/`ClearTaskTargetDate` pair.** Rejected. Two commands for one field whose
  wire encoding already distinguishes set, clear and unchanged, and #348 had already settled the
  newer single-command shape for the deadline clock.

- **Make `SetTaskPriority` nullable for symmetry with the date command.** Rejected. The server
  offers no null form, so the only thing a null could produce is a 422, and the outbox treats a 422
  as Terminal — the mutation would be dead-lettered rather than retried, and the optimistic local
  value would be rolled back.

- **A verb per bucket** (`FireTask`, `BacklogTask`, and so on), mirroring the status verbs. Rejected.
  The status verbs earn separate commands because each carries distinct product semantics. The
  buckets do not, so one set-command with the bucket as its operand is enough, and the binding layer
  picks the affordance from the current value.

- **Route the recurring kinds' writes through `DefinitionWriter`.** Rejected. It is a one-method
  light switch for `DefinitionState`, not a per-field PATCH seam, and widening it is its own change
  with its own tests — with no recurring detail surface on any platform to drive it today.

- **Back-fill `priority` to `Normal` in the migration.** Rejected. A back-fill would write into every
  existing row the same thing its absence already says, and it would make the migration slow and
  destructive for no readable difference.

- **Add both fields to the New form in this pass.** Rejected as unbuildable, on the ground ADR-0051
  had already established: every create handler ignores them, and the payload structs do not reject
  unknown keys, so a create-time field would be accepted by the server and silently dropped.

**Consequences.**

- The client now has **two date axes**, and every surface that says "due" must keep reading
  `completeBy`. A [[Target date]] is not a deadline, is never carried forward, and never appears on
  the calendar. `CONTEXT.md` carries the distinction so the two cannot be renamed into each other.

- **A Habit, Chore or Event round-trips both fields but can be written with neither** until a
  per-field recurring writer exists. That is the visible cost of the read/write asymmetry, and it is
  the named follow-up: a per-field seam beside `DefinitionWriter`, plus a recurring detail surface to
  drive it.

- Every cached row can rank itself offline with the same key the server uses, so a ranked view needs
  no round trip and no `$orderby`. Curated orders are untouched, which means adding the key to a view
  is a local, reviewable decision rather than a global reordering.

- Every captured contract fixture predates both fields and keeps parsing untouched. An absent
  `target_date` and an unrecognised `priority` token degrade to the same values a legacy row means,
  so an item can never rank as more or less urgent than it is because of a token this build does not
  know.

- ADR-0027's derived-priority bullet and the `CONTEXT.md` [[Priority]] entry are marked and rewritten
  in this commit. Anything that still ranks "by priority" from `desire` and `productive` is now
  reading a stale rule.

- Schema 18 is released, so `17.sqm` and the `18.db` snapshot are immutable from here (ADR-0022). A
  later column on these four tables takes the next migration rather than editing this one.

- The New create surface still cannot carry either field, so the two ways to set them are item detail
  and the backend's own clients. Lifting that needs the companion backend change, not a client one.

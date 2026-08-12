# The four-kind wire stays until the backend lands, and recipe round-trip is the gate

**Status.** Accepted (#416).

**Date.** 2026-08-11

**Issue.** #416

**Context.** ADR-0055 re-cuts the client domain model onto plugins. The backend still speaks four
kinds on every route, and it keeps doing so until its own port lands. Offline-first makes that binding
rather than incidental. The outbox holds pending mutations that replay against whatever the server
accepts, so a shape the client can hold but cannot send is a row that never syncs.

The plugin model is strictly more expressive than the four kinds. A maintained condition, held rather
than performed, answers to no kind at all. Persistence policy has five members where the wire derives
one bit from the kind. Obligation and purpose have no wire field anywhere.

A second trap sits in the reference fixtures. `AspectualShapes.kt` gives a recurring chore a
skipped-if-missed policy and an appointment a creates-follow-up policy. Neither is what the client
does today, which is one bit read off the [[Item kind]]. Building the translation from those fixtures
would change what happens to every missed item while claiming to be a re-model.

**Decision.** One [[Recipe]] layer translates between plugin sets and the four kinds, and its
round-trip is a gate on the `check` path.

- **Recipes live in `core/model`** as pure functions in both directions. Wire mapping stays in
  `core/data`, which calls them, so `core/data` keeps its single responsibility.
- **Two recipes per [[Item kind]], not one.** The *parity recipe* reproduces today's behaviour exactly
  and is what the migration is gated on. The *target recipe* is the shape the model wants, and it
  lands later with its own interface, one [[Family]] at a time.
- **Round-trip is identity.** For every kind crossed with every field combination the wire can carry,
  reading into plugins and writing back reproduces the input unchanged.
- **Behaviour parity is asserted rather than reviewed.** The readings derived from plugins equal what
  ships today: `carriesForward` against lapse, `resolveOccurrenceState` against progress, and
  lateness against the temporal anchor.
- **The outbox stays wire-shaped.** A mutation describes a `PATCH`, so its vocabulary follows the
  wire. Only the optimistic `applyTo` moves onto the plugin host, where it becomes a family swap.
- **The [[Occurrence grid]] is untouched.** The recurrence plugin wraps the existing `Recurrence` and
  series inputs rather than restating them, because ADR-0053 pins offline expansion against a
  generated corpus.
- **The recipes are written to be deleted.** They occupy one package with no other responsibility, so
  the cutover removes a directory instead of unpicking a layer.

**Considered & rejected.**

- **Translate inside the DTO mappers, spread across `core/data`.** Wire knowledge does belong there,
  but this scatters the eventual deletion across 62 files and leaves the round-trip property with
  nowhere to be stated as one test.
- **Move the client to a plugin wire immediately, behind a feature flag.** The server does not accept
  that wire, so the flag would gate nothing that works.
- **Adopt the fixture recipes from `AspectualShapes.kt` directly.** They showcase what the model can
  say rather than describing what the client does. Using them would ship a behaviour change nobody
  asked for, disguised as a refactor.

**Consequences.** Nothing a person creates offline can fail to sync, because the representable set is
pinned to what round-trips. The cost is that the plugin model runs clamped below its own
expressiveness for the whole migration, and the families that cannot round-trip need somewhere else to
live, which is ADR-0057.

The parity gate also freezes today's behaviour, including the parts that are wrong. The time-of-day
conflation between a deadline and a start time is reproduced faithfully by the parity recipe.
Correcting it is a target-recipe change with its own issue, rather than a side effect of the
migration, which is the distinction that keeps the gate meaningful.

## Amendment (2026-08, #420): the fixture trap was named against a premise that does not hold

Ratifying the target recipes required checking the parity claim this record rests on, and one half of
it is wrong.

**What this ADR says.** Adopting `AspectualShapes.kt` directly would ship a behaviour change, because
its recurring chore carries a skipped-if-missed policy — *"gone, and the miss is **logged**"* — and
"nothing logs a miss today". `PersistenceSeed` therefore seeds a lapsing kind
`ExpiresAfterWindow`, *"gone, **nothing recorded**"*, and justifies it with the claim that habits and
events are never overdue.

**Why the premise does not hold.** Two shipped readings already record a miss:

- `resolveOccurrenceState` gives a past firing with no stored fact `Missed` on an Active definition
  and `Skipped` on a shelved one. There is **no kind branch in that function** — a missed Habit day,
  Chore day and Event day are treated identically, and the discriminator is the definition's light
  switch. `Missed` is rendered.
- `RecurrenceCursor` reads a missed Habit as overdue since the day its cursor stopped advancing,
  pinned in `RecurrenceCursorTest` against live item #277.

Under ADR-0055's own rule — *store the evidence, derive the label* — a miss that is derived and
rendered is what "logged" has to mean here. Nothing stores a miss row, and nothing was ever going to.

**What stands.** The fixture caution is still right for the *other* policy it names: an appointment
carrying creates-follow-up would mint rows nothing mints today. The distinction between a parity
recipe and a demo recipe is unchanged, and so is the round-trip gate.

**What is corrected.** `SkippedIfMissed` on a live definition is the **parity** answer, not the
behaviour change. `PersistenceSeed`'s kind-derived bit describes only the *carry-forward* coordinate —
whether an undone thing follows you into today — which this client does not compute at all: it is
fetched, since `OfflinePlanRepository.refreshPlan` pulls the day's snapshot and full-replaces. The
ratified target is in `TargetRecipe`, and porting carry-forward offline the way `nextDeadlineAfter`
ports cadence advancement is its own issue.

**Consequence.** The parity seed is not evidence about what a missed item does; it is evidence about
what the vocabulary of kinds stood in for. The behaviour-parity gate keeps its meaning either way —
it was always asserting that the seed is derivable from one bit, never that the bit was right.

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

## Amendment (2026-08, #436): the gate covers a second record, and the vocabulary narrows per kind

**What this ADR says.** Round-trip is identity *"for every kind crossed with every field combination
the wire can carry"*. Written against the four definition rows, which were the only rows the recipe
layer translated.

**What is added.** One dated firing is the second record ADR-0055 models, and it now has a
wire-backed [[Family]] of its own — the [[Occurrence]]-scoped half of Enactment, holding what an
`OccurrenceFact` records. So the gate runs twice, over two corpora, on the same terms.

Two properties are specific to the firing half and have no analogue over a definition.

- **Absence is a value.** A fact is wholly its plugin, with no `Core` beneath it, so a firing carrying
  nothing on record is the *absence of a row* rather than an empty one. The recipe writes no fact for
  it, and a stored `Scheduled` stays distinguishable from that absence — the distinction the derived
  Scheduled-versus-Missed reading rests on.
- **The stored vocabulary is narrower per kind than the type.** `OccurrenceResolution` is the union of
  three endpoints, and no kind holds all five: a habit row has no status column at all, a chore has no
  stored `Scheduled` because absence is its record, and an event has no late arm because its handler
  refuses one. A resolution can therefore round-trip perfectly and still be unsendable, which the
  definition half never had to say. `Clamp` states each kind's set, and the firing corpus is generated
  from it rather than from a table beside it.

**Consequence.** The representable set is now pinned on both records, so the ADR-0057 boundary is
checkable for a value recorded against a date as well as for one recorded against an item. The
narrowing also gives Phase 4 a kind-free form to build toward: *"an appointment cannot be late"* is a
claim about the anchor, which outlives the kind that enforces it today.

## Amendment (2026-08, #421): `complete_by` carries three claims, and the split named only one

**What this ADR says.** The parity gate freezes today's behaviour, *"including the parts that are
wrong"*, and it names one: *"the time-of-day conflation between a deadline and a start time is
reproduced faithfully by the parity recipe"*. `Anchor` splitting `Deadline` from `Appointment` is what
gives those two claims separate names, and `TemporalConflationTest` pins the reproduction as
deliberate.

**What the read facade found.** There are **three** claims on that field, not two. On a Task
`complete_by` is a plain deadline. On an [[Event]] it is a start. On a [[Habit]] or a [[Chore]] it is
the [[Recurrence cursor]] — where the series has walked to, and never a bound. Only the third claim
got its own [[Family]] member. The first two both read as `Anchor.Deadline`, with no field between
them, so nothing in a plugin set says which one a row is holding.

The shipped projection guards that distinction hard. `Item` names its field `recurrenceCursorAt`
rather than `completeBy`, projects it on the recurring kinds only, and its own KDoc says that
conflating the two would make every dated Task read as an exhausted-or-due series. So the reading the
plugin model replaces is *more* discriminating here than the model that replaces it, which is a
direction this migration is otherwise never allowed to travel.

**Why it stands for now.** Reproducing it is what a parity recipe is for. The storage genuinely is one
column, and deciding what an existing instant *meant* is a change to what a person sees — #420's kind
of decision, with its own issue, exactly as this record already argues for the Event half. The shape
of the fix is known, because `Anchor.Appointment` is the worked example of it.

**What is added.** The gap is asserted rather than left to a reader.
`PluginReadParityTest.theRecurrenceCursorIsIndistinguishableFromADeadline` pins the two as byte
identical in the plugin read, and names retiring itself as the signal that the target recipe closed
the gap. Closing it is #439, which wants to land before a Phase 4 surface renders a date: until then
an atom reading "due by" off `anchor` renders a Habit's cursor as a deadline, which is the mis-read
the whole recurring epic keeps tripping over.

**Consequence.** Sufficiency is not the same property as round-trip identity, and this is the case
that separates them. A row can round-trip perfectly — nothing is lost, and the gate is green — while
the plugin read of it still cannot answer a question the shipped projection answers. Every phase that
moves a surface onto plugins has to check the second property too, which is why the read facade landed
with a sufficiency gate of its own rather than leaning on the round trip alone.

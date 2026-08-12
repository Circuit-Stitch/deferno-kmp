# An Item is a Core plus a sparse plugin list, cut along eight meaning families

**Status.** Accepted (#416).

**Date.** 2026-08-11

**Issue.** #416

**Context.** The client models [[Task]], [[Habit]], [[Chore]] and [[Event]] as four unrelated data
classes with no supertype. `CONTEXT.md` already rejects that shape: [[Item kind]] states that the four
are kinds of one converged Item family, and its `_Avoid_` line names "separate Task/Habit/Chore/Event
lanes" as the thing to keep out. The code has been drifting toward compliance on its own. `Item` is
already a kind-blind projection across all four kinds, `RecurringDefinition` already unifies the three
recurring kinds behind one type, and both are shipped and consumed.

Measured against the same four structs, the `DefernoPlugins` experiment found 24 fields declared
identically on all four and 65% of every field declaration repeating another. It also found two
defects that follow from the layout rather than from any single bug. A conversion drops `complete_by`
because the shared snapshot does not carry it. A time-of-day lands on a deadline for three kinds and
on a start time for an [[Event]], with no conversion between the two claims.

The client is already aspectual in discipline, under other names. [[Occurrence state]] is documented
as a reading and never a stored value, [[Recurrence cursor]] is derived at render time, and
offline-first requires caching inputs and recomputing rather than caching a server-derived answer.
Storing the evidence and deriving the label is the plugin model's central rule, so the model fits this
client better than it fits the backend it was written for.

**Decision.** An [[Item]] is a `Core` plus a sparse list of [[Plugin]]s, and the client domain model
names no kinds.

- **`Core` carries identity only** — id, owning org, title, tree position, and sync bookkeeping.
  Everything else is a plugin.
- **The plugin set is closed**, a sealed hierarchy, so the compiler names every plugin that a new
  branch forgot.
- **Plugins are cut along eight [[Family]] axes** — Content, Unfolding, Temporal, Modal, Participant,
  Enactment, Persistence and Linkage. At most one member of a family loads.
- **The list is sparse and every read is total.** An absent plugin reads as its degenerate value, so
  no caller has to handle "absent".
- **Placement is a field, not a type.** A `Scope` enum says whether a plugin belongs to the definition
  or to one [[Occurrence]], and the check becomes a filter rather than a priority cascade over
  overlapping types.
- **Derived readings are never stored.** Aspect, lapse, resolution and attainment are computed from
  which plugins are loaded, which is the rule [[Occurrence state]] already follows.
- **The local schema is dropped and recreated.** The four per-kind tables are replaced rather than
  reshaped, and the change ships as a destructive `.sqm` under the ADR-0022 runbook. Nothing carries
  forward.

**Considered & rejected.**

- **Keep four kinds and widen the projections that exist.** `Item` and `RecurringDefinition` could
  absorb more fields indefinitely. That leaves every write kind-shaped, so each new capability is
  still built four times, and it never reaches the conversion property the re-cut exists for.
- **An open plugin registry keyed by name.** Rejected for the reason the experiment gives: a registry
  is a second source of truth that can disagree with the data. A closed sealed set buys exhaustiveness
  instead, and it survives translation into a Rust enum without dynamic dispatch.
- **Reshape the four tables in place rather than dropping them.** ADR-0022 exists because in-place
  `.sq` edits without a version bump strand existing databases forever. A destructive migration under
  the same runbook keeps that gate intact and costs only dogfood data.

**Consequences.** A conversion between what used to be two kinds becomes a plugin swap inside one
family, and content, labels and modality never move. Two guarantees that named fields gave for free
are bought back at runtime: that at most one member of a family is loaded, and that a plugin sits on
the record its scope names. Both are validation rather than compilation, which is the price of a list.

The four kinds do not disappear at once. They survive as the wire shape under ADR-0056 until the
backend port lands, so the saving in duplicated field declarations arrives later than the modelling
change does. Dogfood installs lose their local database at the flip, which is accepted rather than
mitigated, because the app ships to nobody yet.

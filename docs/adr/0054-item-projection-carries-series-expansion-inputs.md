# The Item projection carries the series expansion inputs, so a tree row expands its grid cold

**Status.** Accepted (#410).

**Date.** 2026-08-05

**Issue.** #410

**Context.** ADR-0053 decided that this client reproduces the [[Occurrence grid]] offline, and #401
delivered the engine: a pure `expandOccurrenceGrid`, pinned against the Rust on a generated corpus,
running on every target. Nothing called it. The expander takes a `SeriesInputs`, and the client had
no wire field, no mapper arm and no column from which to build one, so the whole engine was reachable
from nowhere.

Closing that gap forced one question that the plumbing itself does not answer. The three recurring
domain models obviously gain the inputs. `Item` — the cross-kind projection the [[Item tree]] and the
[[Plan]] render — is a deliberately minimal read model, and it already carries the recurrence rule
plus its cursor. Adding a fourth recurring field to it is a widening of a type whose whole value is
that it stays small.

Two facts decided it, and both were verified live against staging on 2026-08-05 rather than inferred
from the specification. First, the wire ships `series` on the `/items` snapshot, not only on the
detail read, so the data is already in hand on every cold sync. Second, no kind-scoped route is
needed to obtain it, which means a detail-only projection would spend a round trip per row to fetch
something the snapshot had already delivered.

ADR-0053 recorded the opposite shape as an accepted limit for the per-[[Segment]] chain eras: those
ride the detail read, so a cold-booted tree row knows only its current era. That limit is real and
still stands. It describes the chain, not the base block, and reading it as though it covered both
would have imported a restriction the wire does not impose.

**Decision.** The expansion inputs are a first-class part of every recurring read, including the
cross-kind one.

- **`Habit`, `Chore` and `Event` each gain `series: SeriesInputs?`** — populated from the wire block
  on both the detail read and the `/items` snapshot, and persisted so it survives a cache round trip.
- **`Item` gains `seriesId` and `series`** — so a tree row or a Plan row hands the expander a rule, a
  cursor and the inputs together, and gets firing dates back with no detail fetch and no network. The
  rule says how often, the cursor says how far along, and only the inputs say which wall times.
- **Absent is not empty, at every layer** — a `null` block is the backend's deliberate elision, which
  means "this device cannot reproduce that grid" and never "that grid has no exclusions". The wire
  DTO, the domain model, the cache and the Backup file all preserve the distinction.
- **The inputs are stored, and the grid never is** — two kind-neutral tables keyed
  `(kind, definition_id)`, mirroring `occurrenceFactEntity` and its rule that the key is the
  definition id and never the series id. A grid is computed for a window, so caching one would cache
  an answer, which is what ADR-0053 exists to forbid.
- **A block that cannot be read in full yields no inputs at all** — the mapper refuses the whole
  block rather than salvaging its readable parts. Tolerance stays where it belongs, in the DTO, whose
  every field is defaulted so a malformed block cannot fail the single-call `/items` decode.
- **The Backup exporter carries the block faithfully** — ADR-0041 makes `items.json` the API's own
  JSON, and the inputs are the only record of which wall times a series fires on.

**Considered & rejected.**

- **Definitions only, leaving `Item` untouched.** The smaller change, and it keeps the projection
  minimal. Rejected because it would have made the Plan (#385) fetch per row for data the snapshot
  had already delivered, and because "minimal" is not the same as "unable to answer the question the
  screen is asking".
- **Three parallel column sets on `habitEntity`, `choreEntity` and `eventEntity`.** Rejected because
  `exdates` and `overrides` are unbounded lists, and the three generated entity types share no
  supertype, so this means three literal copies of a codec that already carries fourteen recurrence
  columns per kind.
- **One `series_json` blob column.** Rejected because it breaches the no-adapters invariant every
  table here holds, for the same reason the recurrence rule is twelve flat columns rather than one
  blob.
- **Observing the two new tables as a second `Flow` and combining.** Rejected after it failed:
  `mapToList` re-queries asynchronously, so a combined read emits the new inputs beside the stale
  definition list and a freshly created row flickers out of the tree. The inputs are written in the
  same transaction as the definition row instead, which makes an inline read consistent by
  construction.

**Consequences.** The offline expander becomes reachable from the two surfaces that need it most, and
#383, #385 and #390 lose their blocking dependency. The cost is a wider `Item`, one more migration,
and a second codec to keep in step with `SeriesInputs`.

Three arms of the block are decodable but unreachable on the wire today, which the fixtures record
rather than hide. Nothing populates `exdates`, the only writer of a cancelling override is a function
no route calls, and `until_utc` is set only by a split that removes the item from the snapshot. The
captured fixture therefore carries what staging can produce, and one hand-authored row carries the
rest.

Restoring a Backup cannot replay the inputs, because no create payload accepts a `series` block and
the backend re-derives the series from `complete_by` plus the rule. Exporting them anyway keeps the
file lossless and leaves that door open.

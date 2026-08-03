# The client reproduces the Occurrence grid offline, and the server ships the expansion inputs it needs

**Status.** Accepted (#397).

**Date.** 2026-08-03

**Issue.** #397

**Context.** ADR-0001 makes the local database the source of truth and the network a refresh
mechanism. Every dated recurring surface in this client breaks that rule in the same way: it caches
an answer the server computed against *its* clock, on the day of the fetch.

The shape is easiest to see on the Calendar. `OfflineCalendarRepository.refreshWindow` full-replaces
a window of firings, and `observeDay` reads only from that cache. A row fetched on 1 August carries
the state the server derived on 1 August. Opened on 5 August with no network, it still reads
`Scheduled` for a day that has passed. The `occurrenceEntity` table makes the same mistake in its
schema, storing an `occurrence_state` column whose value is a function of today.

The backend states plainly that two of those states are not stored at all. Its
`DerivedChoreOccurrenceStatus` is documented as *"Virtual statuses derived at read time from a
ChoreOccurrence record's absence or presence relative to today"*, and its two extra members over the
stored enum are annotated `Scheduled — future or today, no record` and `Missed — past, no record,
chore Active`. Caching them is caching a derivation whose input moves.

The companion `Deferno` record `docs/adr/2026-08-03-occurrence-state-on-item-reads.md` makes
`today_occurrence` readable on single-item reads and on the calendar. That is the correct fix for a
client that asks the server a question. It does not help a client that must answer with the server
gone, because it ships a better answer rather than the inputs to one.

Reproducing the grid locally requires `expand(dtstart_local, tzid, rrule, until_utc, exdates,
overrides)`. Since #382 this client caches the rule losslessly across all six cadences, plus the
bound, `deadline_time_of_day`, [[Definition state]] and `series_id`. It holds **none** of the other
inputs. The [[Series anchor]] is the blocking one, and it is not merely un-cached: it appears in no
response type in the `Deferno` repository, and `dtstart_local` and `until_utc` together match zero
lines of `contracts/openapi-0.1.json`.

The asymmetry is that the client already originates that anchor. A create payload carries
`complete_by`, and `series_anchor_local` turns exactly that value into `dtstart_local`. From the
first mark-done onward the two diverge, because `complete_by` becomes the [[Recurrence cursor]] and
walks forward while the anchor stays put. So the live `complete_by` can never substitute for it, and
the client is asking for a value it supplied.

Two backend behaviours were measured during this work and are relevant because parity is a decision
below. `derive_segment_view` splits Scheduled from Missed against `Utc::now().date_naive()`. The same
match arm then derives that day's `complete_by` through a helper documented as honouring the user time
zone, and both values populate one `ChoreOccurrenceView` literal. A single struct therefore contradicts
itself, and for a person in `America/Los_Angeles` an unresolved chore due today reads Missed from
roughly 5pm local. Separately, `next_scheduled_date_after` windows 400 days and returns `None`, which
handlers translate into "no future occurrences" and use to clear `complete_by`. A rule whose gap
exceeds 400 days reads as ended, which `RecurrenceCursor` already documents as a shipped mis-read.

**Decision.** The client computes what is a fact about dates and syncs what is a record of what
happened. Seven rules follow, and the first two are cross-repository:

- **The client expands the [[Occurrence grid]] itself.** A shared Kotlin expander in the core takes
  the [[Series anchor]], the rule and the bound and produces firing dates for any window, past or
  future, with no network. It is the one place date arithmetic lives, for all four platforms.

- **The server ships the expansion inputs.** An additive `series` object joins every recurring item
  read, carrying `dtstart_local`, the frozen `tzid`, `until_utc`, `exdates` and the per-instance
  overrides. The zone is the one the series was **frozen** in, never the account's current zone and
  never the device's, so a person who moves country keeps the grid they scheduled. Echoing the anchor
  on every read has a second effect worth having: `series_repair` silently re-anchors from the live
  `complete_by` when a series row is missing, and a value on the wire makes that drift visible.

- **Chain history rides the detail read.** `SeriesChainView.segments` widens from bare identifiers to
  per-[[Segment]] objects carrying each era's rule, its own series inputs and its tombstone flag. The
  entities are already loaded and decrypted when that view is built, so this is a projection change
  rather than a new query. A [[Segment]] bound exists **only** on the series row, which is why the
  backend's own `series_repair` refuses to rebuild a superseded segment: an era that is not shipped
  cannot be inferred by anyone.

- **The client stores facts and never readings.** `occurrenceEntity` is replaced by a table of stored
  resolutions keyed by definition and date — the same identity the write path already uses through
  `OccurrenceTargets` — beside an [[Occurrence coverage]] table recording which ranges have been
  synced. [[Occurrence state]] is never a column. It is derived at render time from the grid, the
  stored facts, [[Definition state]], coverage and today. A day inside coverage with no resolution is
  unresolved. A day outside it is unknown, and says so rather than reading as Missed.

- **Parity is with the Rust, and the Rust is normative.** A golden corpus generated from the backend
  pins the expander: every cadence, month-end and 29 February skipping rather than clamping, the last
  weekday anchor, an inclusive RRULE `UNTIL` against an exclusive `until_utc`, and a count that an
  excluded date still consumes. Daylight-saving transitions are pinned by the corpus **because they
  are documented nowhere**. Only the local-to-UTC conversion has a stated rule, and the instants
  themselves come from the `rrule` crate that `defernodate` delegates to, so the behaviour must be
  captured empirically rather than ported from a specification. Where the server is wrong, the fix
  lands in Rust first and the corpus regenerates. The client follows rather than forking a second
  specification.

- **The [[Plan]] is a curation and is never derived; [[Day's firings]] is derived and always
  available.** Plan seeding reads yesterday's persisted list, is gated by a marker on no wire, is
  written into at create time using the server clock, and expires after thirty days. It is not
  reproducible, and that is a property of curation rather than a client limitation. The Plan
  [[Destination]] renders both, visibly distinguished, so an unsynced day shows its firings and
  states that the plan is unavailable.

- **Gentleness is vocabulary, not suppression.** `Missed` is modelled faithfully, because an on-time
  rate and a completion heatmap cannot be computed without it. The rendered words stay factual, in
  the register the catalog already fixes: a past cursor reads "3 days ago", and the accusatory word
  appears only inside a filter the person explicitly chose. This **reverses the rationale** recorded
  on `CalendarItem`, which argued that mapping a firing to a `WorkingState` was a gentleness win
  because that type cannot express Missed. Discarding data is not how this product achieves
  gentleness. A [[Working state]] of Dropped is fully modelled and merely labelled "Set aside".

**Considered & rejected.**

- **Cache the server's `today_occurrence` and refresh on date rollover.** The smallest change, and it
  keeps every occurrence rule server-side. Rejected because it is the defect restated: a value that
  is a function of today cannot be stored, and offline across midnight it reads confidently wrong.
  Refresh is not a strategy for a client whose premise is that the server may never return.

- **Derive the missing phase from a cached on-grid firing rather than asking for the anchor.** Every
  cached calendar row is a server-authoritative instance, so one sample plus the interval pins a
  stride cadence with no backend change at all. Rejected as a design and kept only as a possible
  bridge: it is bounded by whichever windows happen to have been fetched, it cannot bound where a
  series **began**, it cannot evaluate a count bound, and it fails for a rolling [[Chore]] with no
  cached firing, which is the default mode.

- **Split the parity line — match the server on dates, diverge from it on state.** This was the
  recommendation put forward, on the grounds that the two server behaviours above are defects and a
  client should not copy them. Rejected because it creates two permanent specifications and no
  tiebreak. One truth and one generator is stronger: a person comparing the web client and a native
  client must not be shown two answers, and a defect is then fixed once rather than forked.

- **Seed the plan client-side.** Closest to a literal reading of the offline goal. Rejected because
  it reproduces something that is not reproducible, so the two lists would differ for reasons neither
  side could explain, and there is no per-entry provenance to reconcile on.

- **Keep suppressing Missed.** The shipped position. Rejected because it forecloses occurrence
  statistics entirely, and because it treats as a data-modelling question something this product has
  always answered as a vocabulary question.

**Consequences.** The client gains a real recurrence engine, which is the largest single piece of
logic in the core and the one most exposed to silent drift. The `deferno.contract-fixtures`
convention already exists and is wired only into `core:network`, so extending it to carry the
generated corpus is the mechanism that keeps the port honest. Neither Apple application has a Swift
test target, which is a further argument for keeping every rule in shared Kotlin.

Three changes in the `Deferno` repository become prerequisites: the `series` block, the
`SeriesChainView` widening, and per-entry plan provenance. This contradicts the epic's opening claim
that every issue in it is client-side and that server endpoints are live for all of it. It also
re-orders that epic: #383 is no longer the keystone, because the occurrence half of #383, #385 and
#390 now sits downstream of the anchor existing on the wire.

Two limits are accepted knowingly. A `Custom` rule is not expanded offline, which is parity rather
than regression, because the backend's own expander also refuses it. And a chain era is reachable
only once its item has been opened, since the per-segment inputs ride the detail read rather than the
snapshot, so a cold-booted [[Item tree]] row knows only the current era.

Two backend defects are now on the critical path rather than being curiosities, because parity means
the client would otherwise copy them: the UTC-based Missed split, and the 400-day lookahead reported
as an ended series.

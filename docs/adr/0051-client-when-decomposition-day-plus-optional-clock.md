# WHEN on the client decomposes to day + optional clock; all-day is derived, and the client normalizes locally

**Status.** Accepted.

**Date.** 2026-08-02

**Context.** The **New** create surface modelled an Event's WHEN as two fused, zone-bearing instants
plus a stored `allDay: Boolean` (`NewState.start`, `NewState.end`, `NewState.allDay`). Every other
kind already used the shape the server actually stores — a calendar day (`complete_by`) plus an
optional clock (`deadline_time_of_day`) — and so did this repo's own Task-detail path
(`app/iosApp/.../bridge/Bridge.kt`, "the deadline is two axes"). The Event arm was the outlier.

The server settled this in the companion `Deferno` repo's
`docs/adr/2026-06-10-unified-when-decomposition-all-day-derived.md`:

- Every kind carries an explicit `Option<NaiveTime>`, where `None` **is** "no time". Task/Habit/Chore
  keep `deadline_time_of_day`. Event gets `start_time_of_day` + `end_time_of_day`, *"named for Event's
  intrinsic start/end semantics, not deadline semantics"* — the shape and machinery are identical
  across kinds, but the names carry kind semantics.
- `all_day` is **derived, never stored**: an Event is all-day iff neither clock is set
  (`derive_all_day`). It is read-only on the wire and **rejected as input**.
- `normalize_when_instant` (`backend/src/time.rs`) is the **single producer** of every stored instant:
  it keeps only the submitted instant's *local date* and re-attaches the explicit clock — or the
  inclusive end-of-day sentinel `23:59:59` when there is none. Documented idempotent.

Carrying a client-side flag against a server-derived field cost four concepts for one boolean: the raw
`allDay`, a corrected `eventIsAllDay` reading, a `pinAllDay()` bridge helper to stop the reading
collapsing when a start materialised, and an `addNewEventStartFromRow` wrapper to apply the pin. All of
that lived in two Kotlin/Native bridge files with no test source set.

It also produced a wrong answer. `NewState` is consumed by an **offline-first** create (#185,
ADR-0016): `OfflineCreateWriter` optimistically inserts the row and enqueues the payload, so **there is
no 400 to catch a bad Event**. An invalid create is accepted, stored locally, and fails silently at
sync, long after the person has moved on. Two concrete defects followed:

- An all-day start against a timed end **on the same day** (`23:59:59` vs `09:00` once normalized) was
  accepted client-side and rejected by `Event::validate`. Reachable in two taps from the Calendar FAB.
- A timed start against an all-day end on the same day (`09:00` vs `23:59:59`) was *blocked*
  client-side though the server accepts it.

**Decision.**

1. **`NewState` carries days and clocks, never fused instants.** `date` (`complete_by`, shared by all
   four kinds — the Event's start day too) + a clock **named for its kind**: `deadlineTime`
   (`deadline_time_of_day`, T/H/C) or `startTime` (`start_time_of_day`, Event); plus the Event's second
   axis, `endDate` (`end_time`) + `endTime` (`end_time_of_day`).

   The clocks stay separately named on purpose, mirroring the server ADR. A single shared `time` field
   would be a smaller diff, but a Task's "when it must be done" and an Event's "when it starts" are
   different facts, and merging them would silently relabel one as the other across a kind switch.

2. **There is no `allDay` flag.** `eventIsAllDay` is `startTime == null && endTime == null` — the
   server's `derive_all_day`, verbatim. A reading that disagrees with what the payload sends is now
   unrepresentable, so nothing needs pinning and no bridge helper defends it.

3. **The client mirrors `normalize_when_instant` locally rather than depending on the server for it.**
   One private helper — the day at its clock, else the `23:59:59` sentinel — feeds both:
   - **`eventEndBeforeStart`**, which compares the two edges *as the server will store them*, so the
     client predicts `Event::validate`'s verdict without asking. Load-bearing precisely because create
     is offline-first: the client is the only gate until sync. Compared as `LocalDateTime` in the
     account zone, a monotonic mapping, so it can never accept a window the server rejects.
   - **`toPayload`**, which sends the **already-normalized** instant. `OfflineCreateWriter` stores the
     payload's instant verbatim, so an un-normalized one would leave the optimistic row up to a day
     from what eventually lands, silently correcting itself whenever the outbox drained. Safe because
     the server's normalization is idempotent.

4. **The clock-clearing invariant lives in the component.** `setDate(null)` clears the deadline/start
   clock; `setEndDate(null)` clears the end clock. It was previously enforced twice in the two Apple
   bridges and not at all on Android/desktop.

5. **Starts → Add seeds today, all-day.** A day needs no clock; turning All-day off seeds 09:00. This
   drops the `nowEpochSeconds` parameter threaded through three bridge functions in each Apple app and
   the "next whole hour" rounding, and matches the webui WHEN editor and the Task-detail affordance.

**Considered & rejected.**

- **Patch the three defects on the existing `Instant + Boolean` model.** Rejected. Each fix adds
  another reading of "the effective start" and another place the two all-day readings must be kept in
  sync. The remodel makes all three states unrepresentable instead, and nothing on `main` depended on
  the deleted symbols — `allDay`, `eventIsAllDay` and `eventEndBeforeStart` were all introduced by the
  same unmerged branch, so the migration cost was zero and will never be lower.
- **Collapse to a single shared `time` clock across all four kinds** (as the webui's `timeFields` list
  does). Rejected. It is a smaller diff and would carry the clock across a kind switch, but it merges
  two distinct domain facts. The server ADR deliberately kept the names kind-specific, and this repo's
  glossary reserves against exactly that conflation.
- **Keep the client sending start-of-day instants and let the server normalize.** Rejected. Correct for
  an online client, wrong for this one. `OfflineCreateWriter` stores the payload instant verbatim, so
  the row on screen would disagree with the eventual server row until the outbox drained.
- **Add a Target date row in the same pass.** Rejected as unbuildable today, not as unwanted. Every
  create handler hardcodes `target_date: None`, and `payloads.rs` has no `deny_unknown_fields`, so the
  field would be accepted and silently dropped. Split into its own issue, with a companion backend one.
- **Relocate the residual duplicated bridge codec to `app/shell/commonMain` now.** Deferred. Worth
  doing, but moving the logic before correcting it would have relocated the wrong model.

**Consequences.**

- `NewEventStartField`/`NewEventEndField`/`InstantField` are deleted — the **last ISO-instant text
  input** in the client. Android and desktop render day + clock rows like every other kind.
- The Apple bridges shed `pinAllDay`, `addNewEventStartFromRow`, `newIsAllDay`,
  `newEventEndBeforeStart`, `clearNewDate` and `clearNewEventEnd`. What remains is a
  `Double ⇄ LocalDate/LocalTime` codec plus one `applyWhenPicker` — the twin of `applyDeadlinePicker`,
  which is what `pinAllDay` was approximating. Swift reads `value.eventIsAllDay` /
  `value.eventEndBeforeStart` directly.
- **Behaviour change:** toggling All-day on now *discards* the clocks rather than preserving them
  behind a flag, so toggling back seeds 09:00. That is what the server ADR specifies (§6: "on, it hides
  and **nulls** both time inputs"), and it is the price of having no flag.
- `target_date` — the soft "when I want it done" peer of `complete_by`, fixed in the companion repo's
  `docs/adr/2026-06-29-priority-model.md` — is
  still **entirely unmodelled on this client**, as is `priority`. Deliberately out of scope here: the
  server accepts `target_date` only on `Update*Payload`, and every create handler hardcodes
  `target_date: None`, so a New-form row could not work without a backend change. Tracked separately
  (ADR-0052).
- The residual duplicated bridge block is now ~90 lines of model-free codec, which makes relocating it
  to `app/shell/commonMain` (JVM-testable, already exported to both frameworks) a clean follow-up
  rather than a move of wrong-model logic.

# Migration probe

An end-to-end harness for a Deferno backend migration, from the KMP client's point of view.

A data migration rewrites storage. Nothing about that is observable from a unit test against a real
account, and Deferno's migrations are **lazy and per-user** — they run on the first authenticated
request after a deploy — so the only way to know what one did is to record the account's state on
both sides of the deploy and diff it.

```
probe.py preflight  →  probe.py seed  →  probe.py capture pre
                                                  │
                                       merge + deploy the backend
                                                  │
                              probe.py capture post  →  probe.py verify
                                                     →  the Kotlin parity check
```

## Running it

```sh
cd <repo root>/tools/migration-probe

python3 probe.py preflight     # is now a valid moment to seed, and which build is deployed?
python3 probe.py seed          # creates the fixtures, all titled with the scenario's prefix
python3 probe.py capture pre

# … merge and deploy the backend …

python3 probe.py capture post  # waits for the lazy migration to land before capturing
python3 probe.py verify

DEFERNO_PROBE_DIR=$PWD/runs/m16-habit-local-date \
  (cd ../.. && ./gradlew :core:model:jvmTest --tests '*StagingGridParityTest*' --rerun-tasks)

python3 probe.py cleanup --yes  # delete the seeded items
```

Defaults: staging (`https://app2.defernowork.com/api`), the PAT at `~/staging_pat.txt`, run artifacts
in `runs/<scenario>/` (gitignored). Override with `--base` / `--pat-file` / `--dir`, or the
environment variables `DEFERNO_API` / `DEFERNO_PAT_FILE` / `DEFERNO_PROBE_DIR`. `--scenario` picks
what is under test; there is one today and it is the default.

Stdlib only, deliberately: the two halves may be days apart, and nothing about the environment
should need re-creating in between.

## Structure

| file | what it owns |
|---|---|
| `probe.py` | the CLI and everything that is the same for every migration |
| `common.py` | HTTP, clock framing, row classification, reporting — imported by both sides |
| `scenarios/<name>.py` | one migration's preconditions, fixtures, readiness signal, expectations |
| `core/model/src/jvmTest/…/StagingGridParityTest.kt` | the client-side grid comparison |

The engine captures the client-visible surfaces, diffs them, classifies stored vs derived rows, scans
the blast radius, and checks the change stream and wire shape. A scenario supplies only what is
specific to its migration. `scenarios/__init__.py` documents the six functions a new one implements.

## The surfaces it captures, and why those

Exactly what this client reads:

| surface | client code |
|---|---|
| `GET /items` | `KtorItemSnapshotSource` — the cold snapshot (ADR-0049) |
| `GET /tasks/calendar?start&end&tz` | `KtorCalendarRemoteSource` — the agenda feed (ADR-0011) |
| `GET /items/plan?date&tz` | `KtorPlanRemoteSource` |
| `GET /{kind}s/{id}/occurrences` | the per-definition history strip |
| `GET /items/{id}` | item detail, including the `series` block |
| `GET /activity?since=` | `ActivityRemoteSource` — the delta reconcile (#364) |

It captures these for **every** recurring definition on the account, not just the seeded fixtures.
The account's own definitions are migration candidates too, and unlike a fixture their history is not
reconstructible.

`verify` reports in client terms:

- **cached occurrence facts.** `OccurrenceFactLocalStore` keys facts by `(kind, definitionId, date)`
  (ADR-0053 decision 4), so a re-keyed row is a *delete* at the old date plus an *insert* at the new
  one. Only a range re-sync spanning both converges a device that cached the pre state.
- **the change stream.** How many definitions had stored rows move without a `rev` bump — the rows a
  delta-sync consumer never learns about.
- **wire shape.** The tolerant reader (ADR-0005) absorbs *added* fields; a removed one breaks a
  shipped client, and a missing `series` block would strand the offline expander entirely.

## Field notes

Everything below cost a wrong result to find. Read it before writing a scenario.

**Stored rows and derived rows are different things.** The occurrence endpoints answer with both. A
*stored* row carries evidence of something a user did — `done_at` / `completed_at`, or a status the
grid cannot derive — and is what a migration rewrites. A *derived* row is the rule's grid projected
into the requested window (`scheduled`, `missed`, nothing recorded), recomputed on every read.
Merging them lets a window-boundary shift masquerade as lost history, and lets real lost history hide
inside one. `common.stored_dates` is the split; assert `unchanged` on stored rows only.

**The migration runner is fire-and-forget.** `auth.rs::spawn_lazy_migrations` uses `tokio::spawn`, so
the first authenticated request after a deploy *returns before the migration has touched anything*.
Its "already at the current version" short-circuit is a **per-process** cache, so behind more than
one replica the request that triggers the run and the one that reads the result need not be the same
process. `capture post` polls the scenario's readiness signal and confirms it settled. Give every
scenario a readiness signal.

**Keep "read failed" distinct from "not ready yet".** The first version of the wait loop called the
occurrence endpoint without its required `from`/`to`, every poll 400'd, a 400 read as "not ready",
and with no pause between attempts it burned 1249 requests re-sending the same bad request — and
reported the migration as not landed when it had.

**`complete_by` at create time is the series anchor.** The server stores it as
`series.dtstart_local`. It is not merely a deadline: it decides where the definition's whole grid
starts. Create a fixture with a `complete_by` a year out and its grid fires nowhere near any window
worth capturing — an empty-vs-empty comparison that reads as agreement while proving nothing.

**Compare each grid over a window derived from its own anchor.** A monthly or yearly rule anchored
today fires nowhere inside a ±2-month window. `capture` derives a per-case window and reports how
many cases came back vacuous, because a vacuous case is not a passing one.

**The server rejects `UNTIL` before the start.** An `on_date`-bounded rule has to anchor before its
own bound (`recurrence end date X is before the start Y`), which then decides its comparison window.

**Cloudflare bans the default urllib User-Agent.** `Python-urllib/*` gets `1010
browser_signature_banned` in front of staging. Any other token works; `common.Api` sets one.

**Chores and events take different occurrence payloads.** `PUT /chores/{id}/occurrences/{date}` takes
`{"status": …}`; `POST /events/{id}/occurrences/{date}` takes `{"action": …}` (`SetOccurrencePayload`).

**`GET /{kind}s/{id}/occurrences` requires `from` and `to`.** Omitting them is a 400, not an
unbounded read.

**The Activity ledger is not configured on staging** (503, as of 2026-08-05). Record an unavailable
surface rather than swallowing it — `verify` must never report a check it did not get to make. Item
`rev` is the observable that stands in for it.

**Nothing else must touch the account between `capture pre` and the deploy.** `verify` measures each
fixture against its own pre capture; a check-in made from the app in between shows up as drift.

## The Kotlin half

`core/model/src/jvmTest/…/StagingGridParityTest.kt` asks a question the golden corpus cannot: **does
the grid staging actually serves still match the grid this client expands offline?**

`RecurrenceCorpusTest` pins `expandOccurrenceGrid` to a *snapshot* of the Rust, committed under
`contracts/recurrence-corpus/`. A backend change that moves the grid and regenerates nothing leaves
that test green while every client on the network quietly disagrees with the server. The parity test
closes the loop by replaying the probe's live captures — the server's own `series` block, its
`recurrence`, and the instants its calendar feed actually returned.

It fails on exactly one thing: a case that **agreed before the deploy and disagrees after**. A
standing mismatch is not its business — the server bounds a live grid by things a pure expander
deliberately does not model — so those are reported as an inventory. With no `DEFERNO_PROBE_DIR` it
passes without asserting, so it never fails a `check` on a machine that never ran the probe.

### The standing mismatch it measured

7 of 34 seeded definitions disagreed on 2026-08-05, all for one reason, which the test now names so
it cannot be mistaken for a regression later:

> When a rule yields **no** firing inside the window, `/tasks/calendar` still emits a single row at
> the definition's own `complete_by` — its deadline, not a grid slot — while `expandOccurrenceGrid`,
> which models the rule and nothing else, yields nothing.

It shows on rules that genuinely fire nowhere nearby (`yearly` 29 February, `yearly` interval 2) and
on four shapes the client refuses outright but the server accepts at create time (`day_of_month: 32`,
`nth_weekday: -5`, `yearly month: 13`, `yearly day: 32` — all `not_expandable` in the corpus, all
created successfully on staging). The other 27 agree instant for instant.

## Scenario: `m16-habit-local-date`

`Circuit-Stitch/Deferno#658` · `backend/src/migrations/habit_occurrence_local_date_rekey.rs`.

`set_habit_occurrence` used to default an omitted `date` to `Utc::now().date_naive()`, and that value
becomes the Redis hash **field**. Every habit read surface speaks local dates, so west of UTC an
evening check-in landed a day ahead of the day the user actually checked in. M16 moves those rows
onto the local date, detecting the frame from `done_at`.

**Seeding has a clock window.** M16 only touches a row whose stored date equals its `done_at`'s UTC
date but not its local one. `done_at` is always the server's own clock — no endpoint accepts one — so
the defect can only be reproduced while the two frames disagree: **17:00–23:59 local**, west of UTC.
Outside it a no-date check-in lands where both frames agree, M16 correctly skips it, and the `mover`
fixture proves nothing. `preflight` refuses to seed outside the window.

| fixture | how it is built | what must happen |
|---|---|---|
| `mover` | no-date check-in inside the window | moves UTC date → local date |
| `collider` | check-ins on **both** frames | both survive — `HSETNX` must refuse |
| `backdated` | explicit check-in five days back | untouched (no UTC-frame evidence) |
| `already-local` | explicit check-in on the local date | untouched (`local == stored`) |
| `history` | three back-dated days + one no-date row | only the last row may move |
| `rescheduled` | a future firing moved by reschedule | untouched |
| `chore-control`, `event-control` | marked on the UTC date | untouched — M16 scans habits only |
| `corpus-00…33` | one per distinct shape in the golden corpus | grid controls for the Kotlin half |

### Result of the 2026-08-05 run

All seven equivalence classes passed. `mover` and `history` each moved their one bad row from
`2026-08-06` to `2026-08-05` with `done_at` preserved to the nanosecond — the field was re-keyed and
the evidence left intact, which is exactly the migration's contract. `collider` kept both rows;
`backdated` / `already-local` / `rescheduled` and both control kinds were untouched.

**No stored row moved on any definition the probe did not seed**, including the account's own habits.

Three chores shifted their **derived** grid, all at the window edges: the read gained `to`
(`2026-10-04`) and one lost `from - 1` (`2026-06-20`). That is the `series_scheduled_dates` fix
landing — the query used to bound in UTC instants while answering in local dates, which west of UTC
clipped the window's last local day and leaked the day before its first. All three rows were
`scheduled` / `missed` with nothing recorded on them; no data was written.

Grid parity: **0 regressions**, with the 7 standing mismatches unchanged.

The one client-facing cost is the designed one: `rev` changed on **zero** items, so the two habits
whose stored rows moved did so invisibly to any consumer that syncs on change.

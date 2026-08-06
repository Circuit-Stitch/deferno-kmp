# Golden envelopes

Real responses captured from the staging backend — the contract fixtures for the tolerant reader and
DTO tests (#19). Free-text and `/auth/me` identity are scrubbed; all structural fields are verbatim.

**One documented exception to "capture, don't hand-author".** `items-sample.json`'s recurring elements
were hand-extended (#381/#382) to carry a populated `subtask_template` and the richer cadence/`end`
shapes, because the captured account had **no** item exercising either — every template was `[]` and
every rule was `daily`/`weekly` with no bound, which is exactly why the harness could not see two live
bugs. The added values are structurally faithful to the Rust wire types
(`backend/src/subtask_template.rs`, `backend/src/models/recurrence.rs`) and free text stays scrubbed.
Re-capture from staging as soon as a real account holds such an item, and drop this note.

The same exception now covers the `series` block (#410), and here **part of it is not re-capturable at
all** — not "no account has one yet", but "no code path can produce one":

- **`exdates` is always `[]` on the wire.** `SeriesInputs::from_series` ships the stored vector and
  says why in a comment: *"nothing populates EXDATE today, so this is correct for free the day
  something does"* (`backend/src/models/recurrence.rs`). No handler writes one.
- **`is_cancelled` is always `false` on the wire.** The only writer of a cancelling override is
  `TaskRepository::cancel_recurrence_instance`, and **nothing calls it** — no route reaches it. Every
  override the API can mint today comes from `PATCH …?scope=this`, which hardcodes `is_cancelled:
  false`.
- **`until_utc` is set only by a split**, and a split replaces the item (the head segment leaves the
  `/items` snapshot), so the bound cannot be captured on a row that is still listed.

So the **event** row's block is the shape captured live from staging on 2026-08-05 — a throwaway
recurring event, `PATCH`ed twice at `scope=this`, yielding two overrides ascending by
`recurrence_id`, one moved and one not — with the wall times re-pointed onto genuine slots of that
row's own rule, and the throwaway deleted afterwards. The **chore** row is hand-authored to carry the
three arms above (a Segment bound, an EXDATE, a cancelled override) against genuine `weekly`/`Tue`
slots. The **habit** row deliberately carries **no `series` key at all**, pinning the elision the
backend actually performs — absent is not empty (see `core/model/SeriesInputs.kt`).

| Fixture | Endpoint | Shape |
|---|---|---|
| `auth-me.json` | `GET /auth/me` | `Envelope<AuthenticatedUser>` — `id, username, display_name, role, personal_org_id, org_slug, is_admin, console_url` |
| `items-sample.json` | `GET /items` | `Envelope<[ItemEnvelope<ItemView>]>` — one of **each kind** (task/habit/chore/event); note flattened payload + redundant `kind`. The habit carries a **populated `subtask_template`** (one entry with a `description`, one without — the backend omits the key when empty, #381). The three recurrence rules exercise the **flat** cadence shape and the nested `end` bound (#382): habit `daily` with no `end` (→ the never bound, the only encoding the server emits), chore `weekly`+`days`+`end.after_count`, event `monthly`+`interval`+`on.nth_weekday`(`nth: -1` = last)+`end.on_date`. The **`series` block** (#410) rides three of the four rows differently on purpose: event = captured (two overrides, one moved one not), chore = hand-authored (`until_utc` + `exdates` + a cancelled override), habit = **absent** (the elision), task = n/a |
| `item-detail-event.json` | `GET /items/{id}` (#383) | `Envelope<ItemView>` — the **single-item detail**: the same flattened, `type`-discriminated element `/items` returns, with the detail-only derived fields appended. The event is the rich arm: a **two-era `series_chain`** (ADR-0053 decision 3), whose superseded root carries its bound on `series.until_utc` while its own `recurrence` still reads open-ended — the exact disagreement `SegmentDto`'s KDoc warns about, captured rather than asserted; plus a **real stored `today_occurrence`** (non-placeholder id, `status: dropped`) |
| `item-detail-chore.json` | `GET /items/{id}` (#383) | `Envelope<ItemView>` — the counterpart arms: **no `series_chain` key at all** (a rule that has never changed sends no chain, since the backend only emits the block once `segments.len() >= 2` — absent is the one-era statement, not an empty chain), and the **all-zeroes placeholder** `today_occurrence` (`id` a zero UUID, `status: scheduled`). That placeholder is the distinction the two occurrence tables exist to draw: the field's *presence* is what records coverage (the server answered for the day), the zero id is what withholds the fact (nothing recorded). Its definition-level `complete_by` sits a month out from the occurrence's date, pinning that the cursor is not today's deadline |
| `tasks-sample.json` | `GET /tasks` | `Envelope<[ItemEnvelope<TaskSummary>]>` — summary shape (no `kind`) |
| `plan.json` | `GET /tasks/plan` (**legacy**) | the daily plan as the Task-only endpoint returns it — ordered `[ItemEnvelope<TaskSummary>]`. **The client no longer reads this route** (#385): its handler resolves the day's ordered ids against the server's Task store alone, so a day holding a Habit or Chore comes back `[]`. Retained because it is the only capture whose rows omit `ref`+`sequence` (a brand-new server-seeded row), which is a real wire shape worth pinning. The kind-tagged `/items/plan` the client reads now has **no captured fixture yet** — see below |
| `today-sample.json` | `GET /tasks/today` | `Envelope<[ItemEnvelope<TodayEntry>]>` — `task` **nested** + `priority_score`, `urgency_reason` |
| `settings.json` | `GET /auth/me/settings` | `Envelope<UserSettings>` |
| `error-404.json` | any 404 | error envelope: `{version, error:{code, message}}` (snake_case `code`) |

## Missing: `/items/plan` (#385)

The daily plan the client actually reads since #385 is `GET /items/plan` — the kind-tagged mirror that
returns every planned row (`oneOf{task,habit,chore,event}`, plus an inline `today_occurrence` on the
recurring arms) instead of dropping everything that isn't a Task. It has **no fixture here**, for two
reasons worth recording rather than rediscovering:

1. the route is mounted outside the backend router's `routes!()` macro, so it is absent from
   `../openapi-0.1.json` and `../refresh.sh` will not surface its drift; and
2. capturing it needs a staging PAT that is not in `local.properties`. (The two
   `item-detail-*.json` fixtures above *were* captured on 2026-08-05 once such a PAT was to hand, so
   this reason is a matter of access, not of possibility — `/items/plan` is still owed one.)

Until it lands, `KtorPlanRemoteSourceTest` carries a hand-authored envelope of the same shape — which
is exactly the weaker guarantee this harness exists to replace, since a hand-authored body cannot show
a field the server sends that the client never modelled (how #381 and #382 both shipped).

## The empty-401 case (no fixture file — that's the point)

A request with an **invalid/expired bearer returns `HTTP 401` with an *empty body`** — **not** the
`ErrorEnvelope` the spec advertises. The reader must therefore **synthesize an error from the HTTP
status** whenever the body is absent or unparseable, and only parse `ErrorEnvelope` when a body
exists. This is the mandatory negative test; see `../CONTRACT-NOTES.md` → "Error model".

## How the harness loads them (#19)

These files are the single source of truth for the contract-fixture harness in `core/network`
(`ContractFixtureParseTest`). They are **not** read at runtime: the `deferno.contract-fixtures`
convention plugin embeds each file verbatim into a generated `ContractFixtures` object on the
`commonTest` source set (Gradle task `generateContractFixtures` — the directory, package and object
name come from that module's `contractFixtures { }` block; a sibling directory
`../recurrence-corpus/` uses the same convention to carry the generated occurrence grid into
`core/model`), so the harness loads every fixture
on **every KMP target — JVM, Android host, and iOS — with no platform file IO.** Each fixture is fed
through the *shipping* read path (the tolerant reader + envelope/version handling in `requestApi`)
and asserted against its wire DTO.

Because the embedding is regenerated from these files, **re-capturing a fixture flows straight into
the test**: a breaking shape change surfaces as a failing parse, and a completeness guard fails the
build if a newly-captured fixture has no wired parse handler. (Contrast `../refresh.sh`, which is the
on-demand drift check for the *OpenAPI spec* — a reviewable `git diff`, not a CI gate.)

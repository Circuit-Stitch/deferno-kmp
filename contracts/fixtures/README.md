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

| Fixture | Endpoint | Shape |
|---|---|---|
| `auth-me.json` | `GET /auth/me` | `Envelope<AuthenticatedUser>` — `id, username, display_name, role, personal_org_id, org_slug, is_admin, console_url` |
| `items-sample.json` | `GET /items` | `Envelope<[ItemEnvelope<ItemView>]>` — one of **each kind** (task/habit/chore/event); note flattened payload + redundant `kind`. The habit carries a **populated `subtask_template`** (one entry with a `description`, one without — the backend omits the key when empty, #381). The three recurrence rules exercise the **flat** cadence shape and the nested `end` bound (#382): habit `daily` with no `end` (→ the never bound, the only encoding the server emits), chore `weekly`+`days`+`end.after_count`, event `monthly`+`interval`+`on.nth_weekday`(`nth: -1` = last)+`end.on_date` |
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
2. capturing it needs a staging PAT that is not in `local.properties`.

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
`commonTest` source set (Gradle task `generateContractFixtures`), so the harness loads every fixture
on **every KMP target — JVM, Android host, and iOS — with no platform file IO.** Each fixture is fed
through the *shipping* read path (the tolerant reader + envelope/version handling in `requestApi`)
and asserted against its wire DTO.

Because the embedding is regenerated from these files, **re-capturing a fixture flows straight into
the test**: a breaking shape change surfaces as a failing parse, and a completeness guard fails the
build if a newly-captured fixture has no wired parse handler. (Contrast `../refresh.sh`, which is the
on-demand drift check for the *OpenAPI spec* — a reviewable `git diff`, not a CI gate.)

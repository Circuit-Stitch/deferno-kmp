# Golden envelopes

Real responses captured from the staging backend — the contract fixtures for the tolerant reader and
DTO tests (#19). Free-text and `/auth/me` identity are scrubbed; all structural fields are verbatim.

| Fixture | Endpoint | Shape |
|---|---|---|
| `auth-me.json` | `GET /auth/me` | `Envelope<AuthenticatedUser>` — `id, username, display_name, role, personal_org_id, org_slug, is_admin, console_url` |
| `items-sample.json` | `GET /items` | `Envelope<[ItemEnvelope<ItemView>]>` — one of **each kind** (task/habit/chore/event); note flattened payload + redundant `kind` |
| `tasks-sample.json` | `GET /tasks` | `Envelope<[ItemEnvelope<TaskSummary>]>` — summary shape (no `kind`) |
| `plan.json` | `GET /tasks/plan` | the daily plan — ordered `[ItemEnvelope<TaskSummary>]` |
| `today-sample.json` | `GET /tasks/today` | `Envelope<[ItemEnvelope<TodayEntry>]>` — `task` **nested** + `priority_score`, `urgency_reason` |
| `settings.json` | `GET /auth/me/settings` | `Envelope<UserSettings>` |
| `error-404.json` | any 404 | error envelope: `{version, error:{code, message}}` (snake_case `code`) |

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

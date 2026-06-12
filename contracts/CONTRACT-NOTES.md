# Contract notes — what the spec under-documents or gets wrong

Empirically verified against staging (`app2.defernowork.com`, envelope `version: 0.1`) on 2026-06-06.
These are the load-bearing facts for the network, DTO, and persistence layers. Where the live API
disagrees with `openapi-0.1.json`, **the live behavior wins and is noted here.**

## Auth (ADR-0012, CONTEXT.md → "Personal access token")

- Two security schemes only: `bearer_token` (opaque **PAT**, `Authorization: Bearer <token>`,
  *"the path native mobile clients use"*) and `session_cookie` (web). **No `oauth2`/`openIdConnect`
  scheme exists** — PKCE lives on the Zitadel *web* flow, outside this API.
- `POST /auth/tokens {name}` → `{…ApiTokenView, token}` mints a PAT; the raw `token` is shown **once**.
  It is itself gated by an existing bearer/session, so the first PAT is bootstrapped out-of-band
  (web sign-in → mint → paste). `GET /auth/tokens` lists, `DELETE /auth/tokens/{id}` revokes.
- `POST /auth/sign-in {login_name, password, auth_request?}` returns a `oneOf`:
  `{callback_url, next_step:"complete_callback"}` (OIDC browser leg) or
  `{session_id, factor_types, next_step:"mfa_challenge_required"}`. **Not scriptable** from the client
  surface (the `/challenge` endpoints aren't even in the spec) — do not try to automate login.
- `GET /auth/me` → `AuthenticatedUser{ id, username, display_name, role, personal_org_id, org_slug,
  is_admin, console_url }`. `personal_org_id` / `owner_org_id` is the Org isolation key (ADR-0002).
  As of backend #299 `email` + `avatar_url` are documented (nullable, additive; `avatar_url` always
  `null` today — render an initials avatar).

### Native browser sign-in: OAuth Authorization Code + PKCE (backend #299, ADR-0012)

The proper native login (replacing paste). **Not in `openapi-0.1.json`** — these are browser-redirect /
RFC-standard endpoints outside the data API. Shapes below **verified live against staging 2026-06-10**.
All three live under the existing `/api/` base (build with `appendPathSegments("auth","native",…)`); they
are **unauthenticated** (no bearer). The app ships **zero credential UI** — password + Deferno MFA + Google
SSO all happen in the system browser (RFC 8252: Custom Tabs / `ASWebAuthenticationSession`, never a WebView).

- **Register** (RFC 7591, public client, open + rate-limited): `POST /api/auth/native/register`
  `{ redirect_uris: [..], client_name?, application_type?, token_endpoint_auth_method?: "none" }` →
  `201` envelope `{ data: { client_id, client_name, redirect_uris, token_endpoint_auth_method } }`.
  Cache `client_id` per-install. `redirect_uri` must be on the allowlist: custom scheme
  `com.circuitstitch.deferno://…` (fallback), verified HTTPS App/Universal Link (preferred), or loopback
  `http://127.0.0.1:{port}` (desktop).
- **Authorize** (browser entry): `GET /api/auth/native/authorize?client_id&redirect_uri&response_type=code
  &code_challenge&code_challenge_method=S256&state` → `303` to Zitadel (`auth2.defernowork.com/oauth/v2/
  authorize`, the backend's own `…/api/auth/oidc/callback` is the OIDC redirect). After login the backend
  intercepts the callback (native branch, **no web session/cookie**) and `302`s to
  `redirect_uri?code=…&state=…`. The one-time `code` has a 5-min TTL, single-use, bound to
  `{user_id, client_id, redirect_uri, code_challenge}`. Missing params → `400 missing field client_id`.
- **Token exchange**: `POST /api/auth/native/token { code, code_verifier, client_id, redirect_uri, name? }`
  → envelope `CreateApiTokenResponse { …ApiTokenView, token }` (`ApiTokenView = { id, name, kind,
  created_at, client_id?, last_used_at? }`). `name` tags the device (e.g. "Deferno Android — Pixel 8").
  `client_id` **and** `redirect_uri` are **both required** (the ADR's `{code,code_verifier,name?}` is
  incomplete); a bad code → `400 invalid grant`. The returned `token` is the durable revocable `kind:user`
  PAT; its `id` enables server-side revoke on sign-out (`DELETE /auth/tokens/{id}`, unblocking #310).
- Discovery (`GET /.well-known/oauth-authorization-server`, RFC 8414) is **not reliably reachable** (local:
  `503 OIDC not configured`; staging host-root serves the SPA, `/api/.well-known/…` is `404`) — so the
  client does **not** depend on it; endpoints are derived from the `/api/` base.
- The app still **does not** call `/auth/sign-in` (ROPC, rejected in the backend ADR; it requires
  `X-Requested-With` + an in-flight `auth_request` and is the web login's internal leg).

## Envelope & error model (ADR-0005)

- Success: `{ "version": "0.1", "data": <T> }`. Error: `{ "version": "0.1", "error": { "code":
  "<snake_case>", "message": "..." } }` (no `at` field observed). `version` is pinned at `0.1`.
- **Live floor is `0.1`** — the running backend serves `0.1`; ship the tolerant reader window at
  `[0.1..0.1]` (ADR-0005, amended), widen to `0.2` when backend #300 lands.
- **`401` returns an EMPTY body** (verified), not the `ErrorEnvelope` the spec claims. The reader MUST
  synthesize an error from the HTTP status when the body is absent/unparseable, and parse
  `ErrorEnvelope` only when a body is present. (Filed upstream against the backend.)

## Items: `ItemEnvelope<T>`, polymorphism, discriminator (ADR-0011)

- Every list element is `ItemEnvelope<T> = { ref, org_slug, sequence, type, …#flatten T }`. The
  backend uses Rust `#[serde(flatten)]`; **kotlinx.serialization has no `@flatten`** → model wire DTOs
  as **flat per-endpoint data classes**, not a generic `ItemEnvelope<T>`.
- `/items` carries the closed union `oneOf{task,habit,chore,event}` flattened, **discriminated by
  `type`** (values `task/habit/chore/event`); a redundant `kind` duplicates it on `/items` only —
  ignore `kind`, key off `type`.
- `ref` = `{org_slug}-{sequence}` (e.g. `u-e4h2qk-311`); `owner_org_id` present on full items.
- Per-kind fields: **habit/chore/event** carry `recurrence` + `series_id` + `subtask_template`;
  **chore** adds `cadence_mode`; **event** adds `all_day` + `end_time`; **task** carries
  `next_task_id` + `finished_at` + `attachments` + `comment`. Shared base ≈ 24 fields.
- List vs detail: list endpoints return **summaries** (no `kind`); single-item endpoints return full
  objects. The cache tracks a summary-vs-full hydration state (ADR-0001).
- `/tasks/today` is a *different* payload: `{ task: TaskSummary, priority_score, urgency_reason }`
  (nested `task`), still wrapped in `ItemEnvelope`. `/tasks/plan` is a flat ordered list of
  `TaskSummary` envelopes.

## Status: six enums, condensed at the edge (ADR-0011, CONTEXT.md → "Item state")

Six wire enums with inconsistent casing — model each with explicit `@SerialName` + an `Unknown`
fallback, then condense to clean domain types:

| Wire enum | Values | → domain |
|---|---|---|
| `TaskStatus` | open, **in-progress**, **in-review**, done, dropped | `WorkingState` (Task) |
| `DefStatus` | active, in-review, archived | `DefinitionState` (the "light switch") |
| `OccurrenceStatus` | scheduled, **in_progress**, done_on_time, done_late, dropped | `OccurrenceState` |
| `ChoreOccurrenceStatus` | in_progress, done_on_time, done_late, skipped | (settable subset) |
| `DerivedChoreOccurrenceStatus` | scheduled, missed, in_progress, done_on_time, done_late, skipped | (read superset) |

**Read/write asymmetry:** the client only **sets** a coarse occurrence action — chore
`{in_progress, done, skipped}`, event `{in_progress, done, dropped}` — and the server **derives** the
fine read state (`scheduled`, `missed`, and the `done_on_time`/`done_late` split). Domain
`OccurrenceAction { Start, Complete, Skip }`; the mapper emits the kind-appropriate token (chore
`skipped` vs event `dropped`).

## Mutations: intent-shaped minimal bodies (ADR-0001, #23)

- PATCH payloads are all-optional. **Nullable fields** (`title`, `description`, `complete_by`,
  `desire`, `labels`, `pinned`, `assignee`, settings…) treat `null` as **"clear it"** — distinct from
  omit. **Non-nullable `oneOf` fields** (`status`, `recurrence`, `cadence_mode`, `theme_*`, `mood_*`)
  are omit-only; `null` is rejected.
- A naive `T? = null` model collapses Absent and Null → silent field-clobbering. Instead, build
  **intent-shaped minimal bodies**: each outbox intent emits a `JsonObject` with only the keys it
  changes (value or explicit null per intent). Hard serializer rule: **never emit an absent field.**

## Time-of-day (#348) — verified live against staging 2026-06-12

The deadline/start "WHEN" is split into a **date axis** (`complete_by`/`end_time`, RFC3339 instants) and a
separate **clock axis** carried as `"HH:MM"` strings. The clock fields are **the source of truth** for
the time:

- **Task/Chore/Habit** carry `deadline_time_of_day`; **Event** carries `start_time_of_day` +
  `end_time_of_day`. Additive, nullable; the tolerant reader ignores them where unmodelled.
- **On write the server recomputes `complete_by`**: it takes the *date* of the `complete_by` you send
  (interpreted **in the user's timezone**) and combines it with `*_time_of_day`. Verified: sent
  `complete_by:2026-06-20T00:00:00Z` (UTC midnight) + `deadline_time_of_day:"14:30"` → got back
  `complete_by:2026-06-19T21:30:00Z` (the date landed on the 19th because midnight-UTC is the 19th in
  the user's tz). **Send `complete_by` as start-of-day in the *account* tz** so the intended date
  survives. The clock you put in `complete_by` is discarded — `*_time_of_day` wins.
- **On read the clock comes back as `"HH:MM:SS"`** (note the seconds — parse leniently). When
  `*_time_of_day` is **null**, `complete_by` normalizes to the inclusive end-of-day sentinel `23:59:59`
  — so do **not** render `complete_by`'s clock as a real time unless `*_time_of_day` is present.
- **`all_day` is derived, read-only**: `true` iff both time-of-day fields are null. It is **ignored on
  input** (the client no longer sends it) and kept on the wire one deprecation cycle.

## Attachments / feedback presign (#375) — verified live 2026-06-12

Presign endpoints are **envelope-wrapped** (`{version, data:{attachments:[…]}}`); each
`PresignResponse` carries a **`headers` map the PUT MUST send byte-exact** (real S3 mode; empty in
LocalFs dev). Verified against real S3: the signed set is
`content-length;content-type;host;x-amz-server-side-encryption;x-amz-server-side-encryption-aws-kms-key-id`.
The `headers` map returns only `content-type` + the two `x-amz-*kms` values — **`host` and
`content-length` are NOT in the map**; the HTTP client sets them automatically. Because `content-length`
is signed, the **PUT body length must equal the `size_bytes` sent at presign** (mismatch → 403
`SignatureDoesNotMatch`), so presign with the exact byte count and PUT exactly those bytes. `POST
/feedback` accepts `attachment_ids` as **bare id strings** (the `{id,caption}` object form is optional).

## Sync (ADR-0001, #22)

- **No `?since=`/delta/sync-token; no cursor paging** (offset-only `$top`/`$skip` on `/items`).
  Soft-delete tombstones (`deleted_at`) are visible. Sync stays **last-write-wins** — the client owns
  conflict resolution.
- ⚠️ **`rev` + `updated_at` now appear on the wire** (verified on staging 2026-06-12; a Task read carries
  `"rev":8,"updated_at":…` — they were absent when this doc first said "no `updated_at`/`rev` anywhere").
  The backend added optimistic-concurrency fields; **the client still ignores them** (tolerant reader),
  so the LWW posture is unchanged until we deliberately decide to honor `rev`/`If-Match`.
- Sync model: **full-snapshot pull, reconcile by UUID `id` + honor `deleted_at`.** Expect fan-out
  (per-recurring-def occurrences are date-windowed; per-task comments/attachments are separate GETs);
  hydrate lazily (summary → full on demand).

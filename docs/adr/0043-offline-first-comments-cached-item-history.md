# Offline-first Task comments + a cached server item-history feed

**Status.** Accepted (#197a). Amends the **deliberately online-only** Task-detail extras
(`TaskDetailRepository`) to align them with ADR-0001; closes the "comments, server history (`actions[]`),
and attachment metadata are never cached locally" gap ADR-0041 named. Follows ADR-0042's *pattern* (cache
online-only data locally, observe from the cache) and reuses ADR-0034's outbox-create machinery — but not
0042's "free via the existing snapshot" mechanism (see *History is a list, not a scalar*). **Task-only**
(matching ADR-0042's rollup scope). Sits beside — and does **not** touch — the global local `ActivityLedger`
(#260); see *History sits beside the Activity ledger*.

**Context.** The Task detail's **ACTIVITY** section (the web "Activity thread") is the **last online-only
surface** in the client. `TaskDetailRepository` is explicitly, deliberately not offline-first: `comments()`
is a one-shot `GET /tasks/{id}/comments` returning `List<Comment>?` (`null` = couldn't load), and every
write (`postComment`/`editComment`/`deleteComment`) re-fetches the whole thread rather than merging. There
is **no comment table, no outbox lane, no cache** — offline, the section is empty-or-errored and a typed
comment is silently dropped on post (violating ADR-0001 and "never waste user input"). Separately, the
server derives a per-item **audit history** (`actions[]`), served on `GET /items/{id}/history` and also
carried on every full item; the tolerant reader (`DefernoJson`, `ignoreUnknownKeys`) **drops it**
(`TaskDetailDto` KDoc: "silently ignores the rest (`actions`, …)"). No platform renders history today
("ACTIVITY" == comments everywhere), and **macOS has no ACTIVITY section at all** (deferred in its
`TaskDetailView`).

The comment write path has one wrinkle ADR-0034 did not: the backend does **not** accept a client-supplied
comment id (`CreateCommentPayload` = `{body, is_private}`, no `id`), so the client id is a throwaway local
handle rekeyed on replay, and a lost-response replay can duplicate (see *Accepted tradeoff*). That backend
gap is filed (**Circuit-Stitch/Deferno#559**), but the client is correct without it.

**Decision.**

- **Comments read from the cache as a `Flow`.** A new SQLDelight `commentEntity` table
  (`comment_id` PK, `task_id` indexed, `body` nullable, `created_by`, `created_at`, `edited_at`,
  `deleted_at` tombstone, `is_private`) becomes the source of truth; `comments(taskId)` returns
  `Flow<List<Comment>>` over `WHERE task_id = ? AND deleted_at IS NULL ORDER BY created_at` (ADR-0001). The
  `List<Comment>?` "couldn't load" contract and the `commentsError` state are **deleted** — a local read
  can't fail. On task-detail open a `GET /tasks/{id}/comments` **refresh** reconciles that task's rows
  (upsert server rows, drop server-tombstoned ones), **best-effort**: it never gates render (the feed always
  shows the cache) and its failure is invisible — server gone → the cached rows stand as truth. An empty
  thread is a valid terminal state, and the in-flight refresh shows `commentsLoading`, so "not-yet-loaded"
  vs "genuinely empty" needs no error signal.
  `// ponytail: refresh-on-open only — comments aren't on the /items snapshot, so there's no free reconcile; no background comment sync until a per-item feed proves too stale.`
- **The refresh is outbox-aware — it never clobbers an un-synced write (#143).** The reconcile **skips any
  comment whose id has a pending outbox mutation** (a `comment:<id>` edit/delete, or a `comment-create:`
  create), exactly as the settings reconcile checks the outbox before a refresh can revert an optimistic
  change (`OfflineSettingsRepository`, #143). So an optimistic edit, a local tombstone, or an un-synced new
  comment survives an on-open refresh that fires before the outbox drains. **The outbox is the pending-state
  oracle** — there is no pending side table (see *Considered & rejected*).
- **Edit/delete are plain idempotent outbox mutations.** `EditComment(id, body)` and `DeleteComment(id)`
  optimistically apply (edit sets `body`+`edited_at`; delete sets the `deleted_at` tombstone),
  `target = comment:<id>`, `toRequest()` = `PATCH`/`DELETE comments/{id}`, replayed fire-and-forget. Both are
  naturally idempotent — a re-sent `PATCH body` is safe, and a `DELETE` on an already-gone comment is a 404
  the sender already maps to `SendOutcome.Success` (no stuck queue). **No client-id key needed** for
  edit/delete of a synced comment.
- **Create is offline-first with a throwaway client id.** `PostComment(taskId, body)` mints a client UUID
  (`kotlin.uuid.Uuid`), inserts the optimistic row so the feed shows it immediately, and enqueues
  `target = comment-create:<taskId>:<clientId>`. The id lets an offline **edit/delete of your own just-posted
  comment** address it before it syncs — but the backend **never honors it**, so it is a temporary local
  handle rekeyed to the server id on replay (see *Comment-create idempotency*), **not** an idempotency key.
- **Cache the server item-history on open (no new backend work).** On task-detail open a **best-effort**
  `GET /items/{id}/history` (`Envelope<List<TaskAction>>`) reconciles a new `itemHistoryEntry` table
  (`seq INTEGER PRIMARY KEY` rowid, `item_id` indexed, `recorded_at`, `payload` TEXT) by **replacing that
  item's rows**. Server history is append-only + server-derived (ADR-0011 read/write asymmetry), so a
  wholesale replace is dedup-free even though `TaskAction` carries **no stable event id**, and a single
  authoritative fetch avoids a truncated array clobbering a fuller one.
  `// ponytail: one purpose-built fetch on open, not two half-sources to merge; the feed sorts by recorded_at (server array order as same-instant tiebreak), so seq is a plain surrogate — no AUTOINCREMENT, no kind column (parsed off payload).`
- **History is a list, not a scalar — it is NOT free via the `/items` snapshot.** ADR-0042's attachment
  rollup was free because it reduces to domain **scalars** on `Task` that `ItemSync` already carries; the
  snapshot pipeline is **domain-typed** (`ItemSnapshotSource` speaks domain and "never touches a DTO"), and
  the domain `Task` has no history list, so `actions[]` is discarded before the cache. History therefore
  needs its own per-item fetch + table (above). "No new backend work" still holds (the endpoint exists);
  "free by construction" does not.
- **Faithful, Unknown-tolerant history DTO (ADR-0011).** `TaskActionKind` is a Serde **externally-tagged**
  union — bare strings (`"Created"`, `"MergedIntoParent"`) for unit variants, single-key objects
  (`{"StatusChanged": {"from":…, "to":…}}`) for data variants, **no discriminator**. `TaskActionDto` carries
  `kind` as the **raw wire `JsonElement`**, decoded lazily off the cache by a hand-written `toTaskActionKind`
  shape-dispatch — no `KSerializer`, and crucially **no encoder**: the client never writes history, so the
  cache stores the server's own bytes verbatim (`kind.toString()`), which also keeps an additive/unknown kind
  **recoverable** once the client learns to decode it, instead of freezing it to `"Unknown"`. The decoder must
  accept a **bare `JsonPrimitive`** (not an object serializer, which throws "Expected JsonObject" on a
  string), and `Unknown` must accept **both** a primitive (unknown unit variant) and an object (unknown data
  variant) so additive server kinds degrade instead of crashing. `StatusChanged.from/to` decode as
  `TaskStatusWire` **with an `= TaskStatusWire.Unknown` default** — the wire field is required and
  default-less, so without the default an out-of-vocabulary token throws and aborts the whole array.
  **v1 models Task actions only**: a recurring item's status vocabulary is `DefStatus` (active/archived),
  which `TaskStatusWire` can't name; since history renders only on the Task detail, recurring-kind actions
  stay dropped by `ignoreUnknownKeys` until `StatusChanged` is kind-aware.
- **Peer UUIDs resolve locally or degrade.** `ParentAssigned.parent_id`, `Split.child_id`,
  `FoldedInto.next_task_id`, `MergedChild.child_id`, `Moved.from/to_parent_id` are raw ids; the View resolves
  a title from the **local item caches** and falls back to a generic "another item" when the peer is aged out
  or unsynced. History render **never fetches** and never blocks on resolution. Each verb carries a **typed
  code** the View maps to a localized string (no server-authored English).
- **One chronological ACTIVITY feed with stable row identity.** The component exposes
  `activity: List<ActivityItem>` where `sealed ActivityItem { Comment; HistoryEvent }`, merged and sorted by
  instant. **Each carries a stable id** — the comment id for comments, the history row's local `seq` for
  events — so SwiftUI `ForEach(id:)` and Compose keys can diff rows despite history having no *server* event
  id. Comments are read+write rows (own-comment edit/delete gated by `currentUserId`); history events are
  read-only.
- **Own-comment affordances work with no server: cache `currentUserId`.** The identity that gates
  edit/delete and "You" vs "Member" attribution is read from a **device-local, per-[[Account]] cache**
  (populated at sign-in / hydrate), **not** a live `GET /auth/me` on each open. An Account that signed in
  once knows its own user id forever; a permanently-gone server must never blank out your own-comment
  controls or mis-attribute your comments.
- **New strings land in both catalogs.** Every new user-facing string — the history-verb labels, the
  `Unknown`-kind label, the "another item" peer fallback, and `activity_summary_commented` — is added to all
  5 Compose locale files **and** the Apple `Localizable.xcstrings`, or `L10nCatalogParityTest` (on `check`)
  fails. macOS's ACTIVITY header reuses `shell_destination_activity` (matching iOS; the Compose
  `tasks_detail_section_activity` vs Apple divergence is pre-existing and deliberate).
- **Blind last-writer-wins; no conflict UI (ADR-0001).** Intent-based mutations keep independent fields from
  clobbering; a queued edit/delete landing on a server-changed/removed comment resolves LWW, and a
  server-side soft-delete reconciled on the next open drops the local row (unless it has a pending mutation).

**Offline-first invariant (works with the server permanently gone).** This is the acceptance test for every
choice above: **the local cache is the durable source of truth**, and the app is fully usable on whatever it
holds even if the server never answers again. Concretely — (1) every read (the comment thread, item history,
own-comment affordances, author attribution) renders **from the cache**, never from a live call; (2) every
server touch (the on-open comment/history refresh, outbox replay) is **best-effort enrichment** that can fail
silently and **never gates the UI or loses a write**; (3) the client **mints and keeps its own identities**
(a posted comment's client UUID is its identity in the local store until, and only if, a live server rekeys
it) — it never *waits for* or *depends on* a server-assigned id to function; (4) an un-synced write lives in
the outbox indefinitely, applied optimistically, and is never dropped (the refresh is outbox-aware so it
can't clobber it, #143). A permanently-dead server degrades the app to "no new remote data arrives" — never
to "a feature stops working" or "my input was lost."

**Comment-create idempotency (the rekey is the mainline, not a rare heal).** Because the backend never honors
the client comment id, **every** create replay reassigns a new server id — the divergence ADR-0034 treats as
rare (`if (clientId == canonicalId) return false`) is *universal* here. The create routes through a
response-bearing replay: `sendCreate` parses `{data:{id}}`, and a **`CommentReplayListener`** — a distinct
seam (`(taskId, clientId, serverId)`, no `ItemKind`; a **second** listener constructor param on
`OutboxProcessor` beside the existing `CreateReplayListener`, reusing the shared `sendCreate` envelope
parser) — **rekeys** the optimistic row's `comment_id` clientId→serverId (the established
`pendingItemCreate.rekey` in-place `UPDATE`; no FK hazard, the schema has none) **and re-points any
already-queued `comment:<clientId>` edit/delete** to the server id (the shared `OutboxStore.repointId`
outbox sweep, also used by `ItemIdHealer`; a UUID substring is collision-safe). It **returns `healed=true`
so the processor breaks the now-stale `syncable()` pass** — load-bearing, not optional: without the break the processor replays
the queued edit against the dead client id, gets a 404 → `Success` → drops it, **silently losing the edit**.
Because that break fires on *every* comment-create, **`OutboxDriver` loops `flush()` while a pass made
progress and work remains** (`do { r = flush() } while (r.succeeded > 0 && r.remaining > 0)`) so a burst of
offline comments drains fully on reconnect instead of one-per-tick. A golden test covers
create-then-edit-then-flush in a single pass.

**Comment writes in the Activity ledger.** A comment write rides the `LedgerRecordingOutboxStore` enqueue
choke-point, so it lands in the global ledger for free — but its `comment-create:`/`comment:` target matches
no existing `ActivityLedger.summaryInfo()` case and would render the generic "Updated an item". So: add
`ActivityVerb.Commented` + `summaryInfo()` branches for **both** prefixes (a `comment:` `DELETE` reads as
"deleted a comment", mirroring how the grammar already keys a verb off the method), with
`activity_summary_commented` in both catalogs. Comment ledger rows are **non-deep-linking** (`itemId()`
returns null — the edit/delete target carries the comment id, not the parent task id, and the ledger's
tap-to-open is future-use anyway).

**History sits beside the Activity ledger (do not conflate).** #260's `ActivityLedger` is a **global**,
device-local, outbox-derived journal of *this app's own* writes (source Mobile/Website/Mcp, wiped on
sign-out, keyed by a local `seq`) powering the global **Activity Destination**. `itemHistoryEntry` is
**per-item**, **server-authored**, **complete**, **read-only**, and durable. Different cardinality, source of
truth, and lifecycle — the ledger structurally *cannot* be the source of truth for item history (empty
backfill, only this device, only since migration 8). They stay **two separate stores**.

**Considered & rejected.**

- **A `pendingComment` side table (mirroring ADR-0034's `pendingItemCreate`).** Unearned here: 0034's table
  exists to keep the domain Item a clean projection *and* to protect un-synced creates from the **global
  `/items` snapshot** orphan-purge — but comments aren't on that snapshot (the only reconcile is the per-item
  refresh we fully control), and `commentEntity` is already a mutable cache row. The outbox itself is the
  pending-state oracle the refresh consults (#143); the replay listener rekeys the row in place. No side
  table, no store port, no pending column.
- **Cache history "free by construction" via the `/items` snapshot (like the attachment rollup).** Rejected:
  the rollup reduces to domain scalars `ItemSync` already carries; history is a list with no domain carrier,
  and the snapshot pipeline never sees the DTO — so `actions[]` is dropped before the cache. A per-item
  history fetch on open is the honest cost.
- **Keep comments online-only / a hybrid read.** Contradicts ADR-0001 and drops the user's typed comment
  offline; a hybrid re-introduces the online failure surface ADR-0042 removed.
- **Extend the #260 `ActivityLedger` to absorb server history.** Wrong cardinality (global vs per-item),
  source of truth (local optimistic vs server-authored/complete), and lifecycle (wiped on sign-out). A new
  store is cleaner than bending the ledger.
- **All four kinds / occurrence-scoped comments.** Task-only halves the schema/mapper/UI surface (same call
  as ADR-0042). Item-level comments/history are the upgrade path when other kinds gain a detail ACTIVITY
  section — and are what a `DefStatus`-aware `StatusChanged` would unlock for recurring history.
- **Store comment ciphertext for encrypted-only bodies.** `body` stays nullable and renders the redacted
  placeholder; this v1 client does no comment decrypt.

**Consequences.** The last online-only surface becomes offline-first; the ACTIVITY feed reads from the cache
with no network, queues every write, and shows server history on open. New substrate: **two tables** —
`commentEntity` and `itemHistoryEntry` (**migration `13.sqm` → `databases/14.db`**, ADR-0022; schema
v13 → v14, plain `CREATE`, verified by `verifyCommonMainDefernoDatabaseMigration`) — a `CommentRepository`
observing a `Flow` with an **outbox-aware** refresh, the `TaskActionDto` (raw-wire `kind`) + its
`toTaskActionKind` decoder + `TaskActionKind`→domain mapper, `PostComment`/`EditComment`/`DeleteComment`
outbox intents, a `CommentReplayListener` + the shared `OutboxStore.repointId` id-heal + the processor's
**shared response-bearing route** (`routeFor`, bound to either replay listener), the
**`OutboxDriver` flush-to-quiescence loop**, the `ActivityVerb.Commented` ledger case, the per-Account
`currentUserId` cache, and the macOS ACTIVITY View. `TaskDetailRepository.comments()` drops its `?`;
`commentsError` leaves `TaskDetailState`, and `comments: List<Comment>` becomes `activity: List<ActivityItem>`
— **every** comment render + bridge call site is rewritten (Compose `CommentsSection` and iOS/macOS
`commentsSection` iterate `activity` and pattern-match `ActivityItem`; `commentIsMine`/`commentDateLabel` take
`ActivityItem.Comment`); only the composer/`onPostComment` seam is unchanged. Per ADR-0006 the
mappers/stores/mutations/serializer/listener are TDD'd in `commonTest` (in-memory SQLDelight driver, Ktor
`MockEngine`, Turbine for the new `Flow`s) with a golden-envelope contract fixture captured for the comments
payload **and** the `actions[]` history; generated table code is Kover-excluded, the logic stays inside the
merged ~85–90% gate, `:koverVerify` stays green.

**Accepted tradeoff.** The **offline and dead-server cases are fully correct and dup-free**: a posted comment
lives in the cache under its client-minted id forever, and the app never depends on a reply. The one residual
artifact is strictly a **live-server** edge: a `POST` that commits server-side but whose response is lost, then
replays — since the backend does not (yet) dedup on the client comment id, that can create two server
comments. The response-bearing heal rekeys the local row to the *first* returned id (no transient local
duplicate, and edit/delete-after-post works); a genuine double-create would surface on the next open's
reconcile. **The client's correctness does not hinge on the server**: it mints stable identity and treats its
store as truth regardless. Filing **Circuit-Stitch/Deferno#559** lets a *live* server dedup the
replay (collapsing `comment-create:` to a plain client-id `send`, like item create post-#402) — a nicety for
the live path, not a prerequisite for the client to be correct. Relates to ADR-0001 (offline-first outbox),
ADR-0011 (condense-at-edge + read/write asymmetry), ADR-0022 (migration runbook), ADR-0034 (the create
pattern this adapts), ADR-0042 (the cache-online-only-data pattern this follows), and #260 (the Activity
ledger it sits beside).

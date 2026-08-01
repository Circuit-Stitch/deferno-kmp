# The Activity ledger is an optimistic cache of the server's, merged by a client-minted `entry_id`

**Status.** Accepted (#364). Supersedes the "device-local journal" framing of #260 and the
`LedgerRecordingOutboxStore` KDoc's "server-sourced entries are a follow-up, recorded at the
snapshot-reconcile seam". Implements the KMP-client half of the backend's
`Circuit-Stitch/Deferno` ADR `docs/adr/2026-07-06-activity-ledger.md` — whose stated consequence is that
"the KMP client is demoted from source-of-truth to an optimistic cache".

**Date.** 2026-07-25

**Context.** The client's Activity ledger (#260) was, as built, a best-effort **local mirror of the
outbox**: one SQLDelight row per `enqueue`, captured at a decorator choke-point, `runCatching{}`-swallowed
on failure, **never uploaded**, always tagged `Mobile`, keyed by a device-local `AUTOINCREMENT seq` that
never left the device. Its own design notes anticipated the gap — "Server-sourced (via Website / via MCP)
entries are a follow-up" — and `ActivitySource.Website`/`Mcp` were already modelled, localized and
screenshot-tested with nothing able to produce them.

The backend now owns a real ledger: append-only, org-scoped, actor-attributed, dual-timestamped, and
served by `GET /activity` with a gapless `?since=` keyset cursor over `observed_at`. It also accepts an
optional `activity: { id, at, source }` sibling on 36 mutation routes. So the question is no longer
*whether* the client syncs, but **how two independently-written streams merge without double-counting.**

Three facts constrain the answer:

1. **A device-local `seq` is unmergeable.** It cannot identify the same action to the server, so any
   reconcile that used it would append a second row for every write this device already recorded.
2. **The client's read model was derived from outbox shape** (`target` + `method` + `path`), which a
   server-sourced row does not have — and which is *coarser* than the vocabulary the server ships
   (18 action verbs vs. 10 inferred ones).
3. **Every other pull in this app is a full-snapshot reconcile** that deletes what the server did not
   return. Applied to an append-only, unbounded, paged ledger, that policy would both grow without limit
   and purge history the server simply had not paged yet.

**Decision.**

- **The client mints the merge key.** Every mutation whose route accepts it carries
  `activity: { id, at, source: "mobile" }` with a client-minted UUID. The server files its authoritative
  row under that id, so the optimistic row and its server twin share an identity and the reconcile
  **replaces rather than duplicates**. Minting happens at the single outbox choke-point
  (`LedgerRecordingOutboxStore`) so a new write path is covered for free.
- **The route declares it, and the default fails closed.** `OutboxRequest.acceptsActivityStamp` is set by
  whoever picked the route, beside the path itself, and defaults to `false`. It is *not* re-derived
  downstream by matching path segments: a whitelist written against path shape drifts loose from the
  contract, and a false positive sends an unexpected key to a strict payload → `422` → Terminal → the
  outbox **dead-letters the user's write**. Losing an audit row is cheap; losing a write is not — so a
  route that forgets to declare it, like the ones that genuinely refuse it (`PATCH auth/me/settings`,
  `DELETE tasks/{id}`), simply gets a server-minted id.
- **The ledger records the *unstamped* body.** `activity` is metadata about the change, not a field the
  user changed, and the read-time diff treats every body key as changed — recording the stamped body would
  put a bogus row in the Activity detail sheet and the Task Trail.
- **The ledger's write port takes the ledger's own shape**, `LocalActivityChange`, not an `OutboxRequest`.
  Two of the three recording seams never enqueue, so handing them the outbox's replay type meant building
  a request that would never be sent, carrying an `acceptsActivityStamp` flag each had to document as
  inert. The port also stopped taking a `source` (`recordLocal` is *the* local path — every other surface
  arrives via the reconcile) and takes the apply instant once: a local write's two time axes are one clock
  reading, so writing them from one value is what stops them being two derivations that can disagree.
- **The reconcile is single-flight.** It is fired from three legs (activation, the reconnect edge, the
  periodic tick) and two of those can overlap. Unguarded, both passes read the same watermark and re-page
  the same rows, and the slower one writes its *older* cursor last — rewinding the watermark. A pass that
  finds one already in flight returns; nothing is lost, because the in-flight pass pages until caught up.
- **Reconcile is grow-only, unioned by `entry_id`, server wins** — never a purge. The `?since=` cursor is
  the only durable state and is **replayed verbatim**, never re-derived from a timestamp: the axis is
  gapless by construction, and inventing a watermark reintroduces the skip the backend already fixed once.
- **Two vocabularies coexist in one row.** The server's typed `action_kind` wins where present; the
  outbox-derived derivation remains the fallback for a not-yet-reconciled or pre-#364 row. `action_kind`
  is open (`Other(raw)`) so a stale build renders a newer verb instead of dropping a forensic entry.
- **Attribution is decided in the shared projection, not per platform View.** Whether the server's actor
  or the acting surface names a row (`ActivityAttribution`) is a fact about the row, so the feed row
  carries the *answer* — one typed value, flattened to one token for the Apple bridge — rather than the
  raw `actor_kind` + `source` + `provider` inputs. Handing the Views the inputs meant writing the same
  three-way precedence in Compose and again in Swift, string-matched across the ObjC boundary, where
  nothing would catch the two drifting apart. Same rule as the typed `summaryInfo` beside it (#327).
- **The feed sorts on `occurred_at`, not insertion order.** A reconcile appends rows *older* than rows
  already stored; insertion order would file a colleague's yesterday-morning edit above this morning's own
  work. Migration 16 back-fills `occurred_at` from `recorded_at` so the column is universal and the sort,
  the prune and the rendered timestamp are one value rather than three derivations that can disagree.
- **The local window is a bounded subset of the server's** (180 days local vs. the server's 18 months), so
  a prune can never drop something the reconcile could not re-fetch. A first sync bootstraps from
  `now - 30d` rather than all of history.
- **The reconcile runs on its own 5-minute cadence**, not the 30-second outbox tick, plus immediately on
  activation and the reconnect edge. The flush drains the user's own pending writes (latency they feel);
  this pulls in what happened elsewhere (latency nobody is waiting on).
- **Failure is silent.** Transport, `401`, and the `503` an environment without a configured ledger
  returns all leave the cursor and cached rows untouched. A diagnostics surface must never be the reason a
  sync pass reports failure.

**Considered & rejected.**

- **A separate table for server rows, merged at read time.** Rejected: it makes every read a join and
  pushes dedup into the render path, where it must be re-derived on each surface (feed, Trail, both
  SwiftUI bridges) instead of once at the write.
- **Server-authoritative only — drop the optimistic row and wait for the reconcile.** Rejected: it makes
  the user's own action invisible for up to a tick, on an offline-first client whose entire posture is
  that a local write shows instantly.
- **Letting the server mint the id and healing the local row afterwards.** Rejected: it needs a
  response-bearing replay for *every* mutation (only creates and comments have one today) and reintroduces
  the id-heal machinery #185 exists to work around. Client-minting deletes the problem instead.
- **`?before=` scroll-back paging as well.** Deferred: the destination renders from the local cache, so a
  second pagination axis would be unused code. It lands with infinite scroll, not before.
- **Reusing the ledger to back the per-item Trail.** Deferred: `GET /items/{id}/history` is still the old
  `TaskAction` shape server-side, so ADR-0043/0046 stand unchanged. When the backend re-implements that
  endpoint over the ledger, the Trail's heuristic nearest-time correlation of local edits to server
  history rows (`ActivityItem.takeMatchFor`) can become a real `entry_id` join.

**Consequences.**

- Schema migration 16 → 17: `activityLedgerEntry` gains the server columns plus a `UNIQUE` index on
  `entry_id` (which SQLite treats as distinct-per-NULL, so pre-migration rows coexist), an index on the
  `occurred_at` sort axis, a back-fill of `occurred_at` from `recorded_at`, and a singleton
  `activitySyncState` holding the cursor. The upsert is spelled `INSERT OR IGNORE` + `UPDATE` because
  SQLDelight's default dialect predates `ON CONFLICT DO UPDATE`; the `INSERT` writes only the NOT NULL
  skeleton, since the `UPDATE` sets every authoritative column on both branches anyway.
- The retention prune runs only after a pass that actually merged rows. The reconcile is the only thing
  that grows this table, so a no-op pass has nothing to trim — and the pass fires every five minutes.
- Three mutation surfaces changed route as part of this: comment-delete and occurrence-clear became POST
  soft-deletes, and the whole attachment surface became kind-neutral (`/items/...`). Those were **already
  broken** against the live backend and only surfaced when the contract pin was refreshed.
- The two write paths that bypass the outbox (attachments, convert) record their own rows, with different
  failure semantics: an enqueued write is durable the moment it is queued, so its row is unconditional; a
  failed upload records nothing rather than claiming a change that did not happen.
- 18 new strings across 5 Compose locales and the Apple catalog. Compose's `when` over `ActivityVerb` stays
  exhaustive — a new verb is a compile error until every Compose surface names it — while Swift's `switch`
  keeps a documented `default` safety net.
- **Sign-out cleanup is now load-bearing and still incomplete.** `ActivityLedgerStore.clear()` wipes rows
  *and* cursor together (a watermark outliving its rows would make the next sync resume past them and
  never re-fetch — a permanently truncated feed). But it has no production caller: cleanup relies on
  `AccountDataStore.wipe`, which is a **no-op on iOS, macOS and desktop**. On those targets a signed-out
  account's ledger and cursor survive. Pre-existing, now with more at stake — filed as a follow-up.

# Offline-first: local source of truth + outbox, last-writer-wins

**Status.** Accepted

**Date.** 2026-06-05

**Context.** At envelope `version: 0.1` the Deferno API exposes *no* sync or concurrency
primitives: list endpoints return full snapshots of summaries with no `?since=`/cursor/ETag,
and entities carry `date_created`/`finished_at`/`deleted_at` (soft-delete tombstones are
visible — good) but **no `updated_at`/`rev`**. List endpoints return summaries; single-item
endpoints return full objects.

**Decision.** A local **SQLDelight** database is the single source of truth. The UI observes the
database via `Flow` only — never the network. Refresh pulls **full snapshots** and reconciles by
`id` (honoring `deleted_at` tombstones; rows track a summary-vs-full hydration state). Writes are
**optimistic + queued in an outbox** of **intent-based, idempotent** mutations (`SetStatus(id, …)`,
not patch-from-X-to-Y), replayed FIFO with retry/backoff when connectivity returns. Because the
server cannot reject a stale write at v0.1, conflict policy is **last-writer-wins**; intent-based
mutations keep independent fields (status vs title) from clobbering each other. There is **no
user-facing conflict UI in v1**.

**Why SQLDelight (not Room).** SQL-first (no annotation processor), pure-JVM testable against an
in-memory JDBC driver, explicit indexes/migrations, and KMP-mature across iOS/desktop.

**Considered & rejected.** A full bidirectional sync engine (unsupported by the API); a
read-through cache (forfeits offline writes).

**Forward path.** Filed [Circuit-Stitch/Deferno#297](https://github.com/Circuit-Stitch/Deferno/issues/297)
to add server-authoritative `updated_at` + `?since=` delta pull, with a companion `ETag`/`If-Match`
(or per-entity `rev`) to upgrade blind LWW → **conflict-aware** LWW and O(all) reconcile →
O(changes). The client ships against full-snapshot in the interim and adopts deltas when available.

## Amendment (2026-08, #396): the queue is compacted before each replay

"Replayed FIFO" above says nothing about *which* entries replay, and an append-only queue replays every
one of them. Offline, a user who marks one recurring firing done, undoes it, and marks it done again
queues three writes of the same absolute intent against the same firing. All three then go out on
reconnect. On a flaky connection that is pure churn, and every intermediate write is another chance to
dead-letter a value the next write was about to overwrite anyway.

**Amended decision.** Each replay pass first **compacts** the live queue, then drains it. Where several
queued writes address the same firing (the `occurrence:<Kind>:<id>:<date>` outbox target, one key per
firing per day) and each is an absolute set-state write, only the last survives. Four invariants make
that safe, and they are the load-bearing part of this note:

- **Delete-only.** The pass never rewrites an entry, never re-sequences one, and never moves anything
  earlier in the queue. The survivor of a collapsed run is the latest entry, keeping its own sequence
  number. Deleting elements from a totally-ordered sequence cannot reorder the elements that remain, so
  every surviving entry replays exactly where it would have, and the FIFO guarantee above is intact for
  intents this rule does not touch.
- **Only absolute per-firing intents collapse.** A reschedule is a hard barrier: it moves the firing to
  a different day, so it is an absolute write over two keys rather than one, and it splits its key's run
  in two. Nothing collapses across it, even where the server would tolerate it.
- **Compaction happens at flush, not at enqueue.** Enqueue does not participate in the replay pass's
  lock, so a merge on the way in could land between the pass's queue snapshot and its send, which would
  dispatch the stale bytes and then delete the merged row. Compacting inside the lock the pass already
  holds has no such race and needs no schema change.
- **Dead-lettered entries are invisible.** One is never deleted by the pass (it is preserved
  deliberately, and the reconcile guards still read it) and never acts as a barrier, because an entry
  that can never reach the server cannot separate two writes that can.

**Consequence.** The activity ledger records a local row at enqueue, upstream of any compaction, so a
coalesced-away write keeps an unacknowledged local row until it is pruned. That is deliberate. The user
did perform the action, so the row is honest, and the alternative — compacting above the ledger — would
silently delete activity rows instead of merely leaving one unmatched.

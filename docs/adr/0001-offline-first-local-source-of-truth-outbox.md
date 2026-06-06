# Offline-first: local source of truth + outbox, last-writer-wins

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

**Forward path.** Filed [Kyle-Falconer/Deferno#297](https://github.com/Kyle-Falconer/Deferno/issues/297)
to add server-authoritative `updated_at` + `?since=` delta pull, with a companion `ETag`/`If-Match`
(or per-entity `rev`) to upgrade blind LWW → **conflict-aware** LWW and O(all) reconcile →
O(changes). The client ships against full-snapshot in the interim and adopts deltas when available.

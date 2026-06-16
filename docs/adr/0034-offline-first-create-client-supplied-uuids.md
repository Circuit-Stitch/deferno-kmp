# Offline-first Item create with client-supplied UUIDs (supersedes the create half of ADR-0016)

**Status.** Accepted (#185). Supersedes ADR-0016's "create is online-only" decision; **convert stays
online-only** (ADR-0016 still governs it).

**Context.** ADR-0016 made v1 create online-only for one reason: the v0.1 API assigned the Item id
server-side with no idempotency key, so a queued+replayed create could **duplicate** (ADR-0001
reconciles on `id`). It explicitly named the forward path — client-supplied / idempotent ids — and
filed it. That backend work is now done (**Kyle-Falconer/Deferno#402**, closed): `POST /{kind}` accepts
a client-supplied `id` and dedupes a create on it. The asymmetry ADR-0016 accepted ("edit offline, but
create only online") can now be removed.

**Decision.** **Create is offline-first**, like every edit (ADR-0001). The
[`OfflineCreateWriter`](../../core/data/src/commonMain/kotlin/com/circuitstitch/deferno/core/data/create/OfflineCreateWriter.kt):

1. mints the Item's UUID client-side (`kotlin.uuid.Uuid`, no new dependency),
2. inserts the optimistic local row immediately (so the observe `Flow` shows it offline or online),
3. records the create in a sparse **`pendingItemCreate`** side table (state `pending`), and
4. enqueues a `POST /{kind}` carrying that id (a `CreateMutation`) on the existing outbox.

The client id is the idempotency key, so a replay after an interrupted request never duplicates. The
command executor returns `Accepted(itemId = clientId)` — never `Offline` — for a create.

**Pending-create state lives off the Item models.** The replay lifecycle is tracked in the side table
(`item_id`, `item_kind`, `state`, `canonical_id`), not as a transient flag on the domain Task/Habit/
Chore/Event (ADR-0001 keeps those a clean projection of server truth). Only `pending` rows protect their
Item from the reconcile orphan-purge — the create-side mirror of the settings reconcile reading the
outbox so a refresh can't clobber an un-synced change (#143). Confirmation is tied to replay: the outbox
processor routes a `create:` entry through a response-bearing `sendCreate`, and a `CreateReplayListener`
confirms the row (or, on terminal rejection, undoes the optimistic insert).

**ID healing (the rare path).** Because #402 honors the client id, the server's canonical id normally
equals it. If it ever **diverges**, `ItemIdHealer` re-points every local reference — the Item row,
Task parent/child refs, plan rows, and any already-queued outbox entry targeting the client id — to the
canonical id, before the processor advances to a queued edit against it. Covered by tests even though it
is not the normal path.

**Convert stays online-only.** Converting an existing item's kind has no client-id idempotency story
(it mutates a row that already exists), so it keeps the ADR-0016 connectivity gate
(`CommandKind.ConvertItem.onlineOnly = true`): online → POST + reconcile; offline → "reconnect to save".

**Consequences.** The visible offline asymmetry ADR-0016 accepted is gone — create and edit are both
offline-first. New substrate: the `pendingItemCreate` table (schema v6, migration `5.sqm`), the
`PendingCreateStore` port, the `CreateMutation` intents, the `OutboxRequestSender.sendCreate` response
path + `CreateReplayListener` seam on the processor, and `ItemIdHealer`. The New surface no longer
disables create when offline. `CommandKind.CreateItem.onlineOnly` flips to `false`.

**Accepted tradeoff.** Only `pending` rows protect from purge; a just-confirmed row relies on the
post-2xx snapshot including it (true under normal read-after-write). Keeping confirmed rows is a small,
bounded audit trail; pruning them is a future cleanup. Relates to ADR-0001 (offline-first outbox),
ADR-0016 (the decision this supersedes for create), ADR-0022 (the migration runbook this followed).

# v1 create is online-only; edits stay offline-first

> **Status: the create decision is SUPERSEDED by [ADR-0034](0034-offline-first-create-client-supplied-uuids.md)** (#185).
> The backend now accepts client-supplied Item UUIDs (Kyle-Falconer/Deferno#402), so the forward path
> below is realized: create is offline-first like every edit. **Convert remains online-only** under this
> ADR. The rest of this record is kept for history.

**Context.** ADR-0001's offline-first write model is an outbox of **intent-based, idempotent
mutations targeting an existing `id`** (`SetStatus(id, …)`, not patch-from-X-to-Y), and the command
layer accordingly has **no create command** — every Command targets an existing entity.

The Android wireframes require creating Tasks/Habits/Chores/Events. The backend create payloads
(`CreateTaskPayload`, `CreateHabitPayload`, …) carry **no client-supplied `id` and no idempotency
key**: the server assigns the id, with no dedup. So a create that were queued and **replayed after a
network blip would risk duplicate items**, and the client would have to reconcile server-assigned ids
across the outbox, the daily plan, and parent/child refs. For Deferno's neurodivergent-first audience,
duplicate clutter is an anxiety cost, not merely a bookkeeping one (design-principles #1, #4).

**Decision.** **Create is an online-gated command in v1.** It calls the create endpoint **directly**
and **requires connectivity**; the server-assigned id seeds the local SQLDelight row, which then
participates in the normal offline-first observe + edit flow. Offline, the create surface shows a
gentle "reconnect to save" rather than enqueuing a create the server cannot yet dedup. **All edits
remain fully offline-first and unchanged from ADR-0001.** Create stays *off* the outbox until the
server can make it idempotent.

**Forward path.** Filed **Kyle-Falconer/Deferno#307** for a **client-supplied / idempotent item id**
(sibling of ADR-0001's delta-sync forward path, Kyle-Falconer/Deferno#297). With an idempotent create,
the client can generate a local UUID, insert optimistically, and enqueue the create like any other
mutation — promoting create to a normal offline-first outbox operation. Adopt when available.

**Consequences.** v1 has a visible offline asymmetry — **edit offline, but create only online** —
accepted in exchange for zero duplicate risk. The shared command registry (ADR-0007) gains a create
command flagged **online-only**, so the agent and OS-intent layers surface the connectivity
requirement rather than silently failing. The asymmetry is revisited the moment the backend gains
idempotency.

**Rejected.**

- **Best-effort offline create now** (client UUID + optimistic insert + local→server id
  reconciliation) — the server has no idempotency, so retry-after-partial-success duplicates items;
  rejected as worse-than-no-offline-create for this audience.
- **Deferring create entirely from v1** — contradicts the whole wireframe set; create is core.

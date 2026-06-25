# iOS Assistant: a thin client over the backend's server-mediated conversational AI, distinct from the propose-only Agent

**Status:** accepted (amends ADR-0015's frozen Destination set; a scoped, deliberate exception to ADR-0001/0034 offline-first; sibling to ADR-0027, which owns the **Agent** — a different capability).

**Context.** The backend shipped an **Assistant**: a per-[[Org]] **server-mediated conversational AI**
(`/orgs/{org_id}/assistant…`) — availability (`entitled && enabled`), an SSE-streamed turn,
server-applied gated proposals (`…/assistant/apply`), recent-conversation listing, Owner enablement
(egress consent), and a shared monthly token pool. This is architecturally *opposite* to the client's
existing **Agent** (ADR-0027): the Agent is client-side, on-device-first, propose-only, and commits
through the offline outbox; the Assistant runs end-to-end on the **server**, egresses decrypted item
content to a third-party AI sub-processor, and **writes itself** (tagging changes `actor = assistant`).
The client has **no chat/SSE code today** (`core/network` is request/response only — no
`ktor-client-sse`), and the glossary even listed "assistant" as a word to avoid. "Implement the
Assistant for iOS" therefore means a **net-new feature**, not an extension of `core/agent`.

**Decision.**

- **A new `feature/assistant` KMP slice** (shared `commonMain`/`iosMain` logic per ADR-0003/0004; the
  SwiftUI View ships first, Android/desktop Views follow) is a **thin chat client** over the backend
  endpoints. `core/agent` is untouched; the **only** shared UX is the **propose-then-accept review
  card**, rendered **inline in the chat** (never routed to the [[Inbox]]).
- **A new top-level [[Assistant]] Destination, entitled-gated** — absent from the nav suite when
  `!entitled`; an in-place **enable + egress-consent** state (surfacing the API's one-time disclosure
  string) when `entitled && !enabled`; chat once `available`. [[Availability]] is fetched for the active
  [[Org]] (the personal org in v1) **before** the nav suite is built. This **amends ADR-0015's frozen
  Destination set**. It is the one **conditionally-present** Destination; the nav is a reveal drawer on
  both platforms (no bottom bar / `NavSlot` split anymore), so its placement is just its drawer-row order
  — **after Tasks** (`Plan · Calendar · Tasks · Assistant · Inbox · Activity · Profile · Settings`).
- **Enablement lives in both the Destination and Settings.** The full enable + egress-consent flow is in
  the Destination's `entitled && !enabled` state **and** mirrored as an Assistant row in
  [[Settings]] (alongside the inference-engine [[App setting]]), so the Owner has a persistent
  disable/withdraw-consent entry point (User Story 17). The consent disclosure is shown wherever the
  Assistant is enabled.
- **Online-only, with locally-cached [[Conversation]]s.** Extending a Conversation needs a live
  connection (the composer is disabled offline; turns are **never** outbox-queued — a conversation is a
  live stream, not a replayable Mutation). Each Conversation is **cached locally as it streams**, so
  past chats read offline and resume. A **scoped, deliberate exception to offline-first (ADR-0001/0034)**.
- **A multi-conversation switcher + cross-device hydration, in v1** (the get-messages endpoint
  `GET …/assistant/conversations/{id}` landed, so both are no longer blocked). The switcher lists cached
  Conversations plus server-listed ids (seeded as placeholders, hydrated on open). Opening a Conversation
  is **local-truth + server backfill**: the local cache renders immediately and the server copy merges in
  any messages the cache was missing (e.g. a turn started on web). The local cache stays the source of
  truth for readable history. *Deferred:* only the usage-meter UI (v1 hard-stops at exhaustion).
- **Streaming behind a fake-able `AssistantStream` seam** with a *provisional* SSE event model
  (`text-delta / tool-call / tool-result / proposal / usage / done / error`), TDD'd under the merged
  coverage gate (ADR-0006). The iOS transport is **Swift URLSession SSE bridged into the seam** (the
  native-engine pattern of ADR-0029/0037): Kotlin owns auth (Bearer PAT) and base URL and hands the
  Swift transport a fully-formed request descriptor; Swift streams events back. The real backend wire
  format is reconciled before the live endpoint is wired.
- **Gated proposals apply server-side.** Only destructive/bulk/cross-[[Org]] changes become a `proposal`
  SSE event; the Assistant performs ordinary writes itself mid-turn. A proposal is confirmed on the
  inline card and executes via `POST …/assistant/apply` (re-checked server-side); rejecting is a
  client-side discard. After apply, the client **re-syncs the affected items** through the normal sync
  path. This **bypasses the [[Command]]/outbox write seam** — here the server is the writer.

**Considered and rejected.** *Extending `core/agent`* — its one-shot, on-device, propose-only inference
seam is the opposite trust/transport model; forcing the Assistant through it would distort both.
*A shell overlay (the Brain dump precedent)* — a resumable, scrollable chat is a sustained *place*, not
an ephemeral invocation. *Outbox-queued turns* — a turn replayed hours later against stale context is
incoherent and can't stream a reply. *Shared `ktor-client-sse` on the Darwin engine* — buffering risk on
NSURLSession; the seam keeps it as a later option for the Android/desktop transports. *Routing proposals
into the [[Inbox]]* — needs a second commit path (the Inbox commits client-side via `CreateItem`; a
proposal applies server-side) and stalls the conversational turn.

**Consequences.** The remaining **backend dependency** is the **turn-stream endpoint** (path, body, SSE
event taxonomy — Deferno#485); the request/response endpoints (availability, enablement, apply,
conversations list, and the **get-messages** `GET …/assistant/conversations/{id}`) exist and are wired
behind a `RemoteSnapshot`-returning `AssistantClient` in `core/data`. The iOS framework export list
(`app/iosApp/build.gradle.kts`) gains `:feature:assistant`; the shell gains a conditionally-present
Destination (dynamic nav from availability) plus a `ShellBridge` accessor; `app/iosApp` gains the Swift
SSE transport injected at `DefernoRoot`. Items written with `actor = assistant` can reuse the existing
external-provenance row mark (fast-follow). v1 ships availability + gated Destination, the consent flow
(in both the Destination and Settings), streaming turns persisted as they stream, inline proposal +
server apply + re-sync, the local cache with a multi-conversation switcher and cross-device hydration,
and the usage-exhaustion hard-stop; **deferred**: only the usage-meter UI and the Android/desktop Views.

The shared core landed first, TDD'd under the merged coverage gate: `core/model` (Conversation /
ChatMessage / AssistantProposal / Availability + ConversationDetail), `core/network` (DTOs + mapper),
`core/database` (the `conversationEntity` + `conversationMessageEntity` tables, migration 8→9),
`core/data` (`AssistantClient`/`KtorAssistantClient` + `ConversationStore`/`SqlDelightConversationStore`
+ DI), and `feature/assistant` (the `AssistantStream` seam + `DefaultAssistantComponent` state machine).

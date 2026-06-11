# Client-side agent: Koog in the shared core, hosted inference behind a thin Deferno relay, propose-only v1

**Context.** Two LLM-backed features are planned: the **Brain dump** extractor (CONTEXT.md, Stage 2 —
a spoken [[Transcript]] becomes several draft Tasks) and a **plan curator** (a proposed delta over the
server-seeded daily plan). Three standing constraints shape the design: privacy is structural — speech
recognition never touches the cloud and nothing off-device may happen silently (ADR-0009/0018); the
client is Apache-2.0 open source (ADR-0020), so a Deferno-owned provider key can never ship in the
binary; and the backend owns the domain language and already auto-seeds the daily plan.

**Decision.**

- **Agent logic lives client-side**, in a new `core/agent` KMP module (`deferno.kmp.library` +
  `deferno.di`, iOS target included), built on **Koog 1.0 — stable modules only** (provider
  abstraction, `@Serializable` structured output with repair). One implementation serves every
  inference tier; the beta modules (`agents-mcp`, LiteRT) are out of v1.
- **Inference is tiered: local preferred when available, off-device only by explicit opt-in, never
  silent.** Launch sequencing inverts the end-state on purpose: **v1 ships hosted-only** (still
  opt-in) to exercise the full pipeline; local engines follow (desktop Ollama, Android LiteRT when
  its Koog module matures; iOS deferred with its app). Engine choice and the opt-in are **App
  settings** (device-local); the relay entitlement is per-Account.
- **Hosted inference sits behind a thin Deferno-operated relay** — the OSS constraint forces the
  provider key behind a Deferno-operated endpoint. The relay is an **Anthropic-format passthrough**:
  PAT auth → entitlement check → verbatim forward. The client points Koog's stock
  Anthropic client at the relay URL — zero custom wire code, and Claude-native structured output and
  prompt caching survive. Prompts ship with the app; improving them is an app release.
- **The agent is propose-only: it holds no tools and no write access.** It maps context to a typed
  proposal — draft Tasks (decomposition + sequence + `completeBy` + `desire`/`productive`) or a plan
  delta — and the *person's acceptance* commits through the **existing Command path**: `CreateItem`
  for accepted drafts (online-only, ADR-0016) and the `AddToPlan`/`RemoveFromPlan`/`ReorderPlan`
  Commands for accepted plan deltas (offline-first through the outbox via `OutboxPlanWriter`). The
  Command registry (ADR-0007) remains the designated seam for a future tool-holding agent; it gains
  no LLM caller in v1.
- **Extraction targets only fields the domain already has.** "Priority" is a *derived* reading of
  `desire`/`productive`/`completeBy`, never a stored field; "dependencies" are the existing
  **sequence** (`nextTaskId` chains) and **decomposition** (`parentId` trees) relations. No new
  backend fields, no blocks/blocked-by edge.
- **Surfaces:** Brain dump is a **mode on the New surface** (beside the dictation mic, ending in a
  draft-review list); the plan proposal is an affordance on the **Plan** Destination opening a
  review-the-delta sheet. The frozen v1 Destination set (ADR-0015) is unchanged.

**Considered and rejected.** *BYO user API key* (not in v1 — remains additive later as another
engine-catalog entry). *Backend agent endpoints* (duplicates agent
logic in two languages the moment a local tier exists). *Server-fetched prompts* (a versioned
cross-repo prompt contract isn't warranted yet). *Write-tool agent in v1* (guardrail design without a
v1 need — both flows are propose-then-confirm). *Strict on-device only* (no iOS path, weak extraction
quality; human review already bounds model risk). *OpenAI-compatible facade on the relay* (the
translation layer drops provider-native structured output — the capability extraction depends on
most).

**Consequences.** The backend repo (`Kyle-Falconer/Deferno`) gains service-side prerequisites: the
relay endpoint and entitlement exposure. The accept paths need **no new
write plumbing** — `CreateItem` and the three plan Commands already exist and route through the
executor; the new client work is `core/agent` itself plus the two review surfaces. `core/agent` sits
under the merged shared-core coverage gate, so the Koog boundary must be isolated behind a fake-able
engine interface. Any service-side accounting happens at the relay, preserving ADR-0009's
no-client-analytics stance.

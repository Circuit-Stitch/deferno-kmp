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
- **The inference engine is a single device-local choice, defaulting to Off.** The person picks from a
  2-to-many catalog — **Off** (default; the agent stands down; AI is never forced on, and a later
  onboarding step asks whether to use AI at all), **on-device** engines (ungated, available to
  everyone), and **Deferno-cloud** engine(s) (per-Account entitlement). There is **no separate opt-in
  toggle**: *selecting a cloud engine is the explicit opt-in*, and nothing off-device happens silently.
  Gating is **per-origin** — every cloud model needs entitlement, every on-device model is free — never
  the whole agent and never per-model (an engine *hosts* a model; the model sub-pick is deferred, v1 is
  one global agent-model). Launch sequencing inverts the end-state on purpose: **v1 ships cloud-only**
  (no on-device engine yet → effectively premium-first) to exercise the full pipeline; on-device
  engines follow (desktop Ollama, Android LiteRT when its Koog module matures; iOS deferred with its
  app). The engine choice is an **App setting** (device-local); the relay entitlement is per-Account,
  server-enforced (the client reads a fake-able flag).
- **Hosted inference sits behind a thin Deferno-operated relay** — the OSS constraint forces the
  provider key behind a Deferno-operated endpoint. The relay is an **Anthropic-format passthrough**:
  PAT auth → entitlement check → verbatim forward. The client points Koog's stock
  Anthropic client at the relay URL — zero custom wire code, and Claude-native structured output and
  prompt caching survive. Prompts ship with the app; improving them is an app release.
- **The agent is propose-only: read-only lookup tools are allowed; write tools are not.** It may use
  deterministic reference-lookup tools over the local Item cache/search indexes to resolve existing
  Items the person mentions; lookup never queries the server. It then maps context to a typed
  proposal — draft Tasks (decomposition + sequence + `completeBy` + `desire`/`productive`) or a plan
  delta. The *person's acceptance* commits through the **existing Command path**: `CreateItem` for
  accepted drafts (offline-first once client-supplied Item ids land) and the
  `AddToPlan`/`RemoveFromPlan`/`ReorderPlan` Commands for accepted plan deltas (offline-first through
  the outbox via `OutboxPlanWriter`). The Command registry (ADR-0007) remains the designated seam for
  any future write-capable agent; v1 gets no write tool caller.
- **Extraction targets only fields the domain already has.** "Priority" is a *derived* reading of
  `desire`/`productive`/`completeBy`, never a stored field; "dependencies" are the existing
  **sequence** (`nextTaskId` chains) and **decomposition** (`parentId` trees) relations. No new
  backend fields, no blocks/blocked-by edge. Existing Items are referenced by their human-facing
  `ref` in agent-visible proposals and resolved back to UUIDs by the client before commit; draft Items
  may use opaque local ids in the same structural slots until acceptance. Stale or unresolved refs
  preserve the draft and enter a repair path instead of being silently dropped.
- **Surfaces:** Brain dump is a **mode on the New surface** (beside the dictation mic, ending in a
  draft-review list); the plan proposal is an affordance on the **Plan** Destination opening a
  review-the-delta sheet. The frozen v1 Destination set (ADR-0015) is unchanged.

  > **Amendment (#150).** Brain dump shipped as a **dedicated shell top-bar overlay**
  > (`OverlayRoute.BrainDump`, launched from the shared `ShellChrome` `voice_chat` action) rather than a
  > mode on the New surface — the reveal-drawer chrome gained its own brain-dump affordance, so a separate
  > overlay reads cleaner than overloading New. It is a **continuous** dictation session (the whisper floor
  > honours `ContinuityHint.Continuous`, accumulating utterances across pauses) feeding the `Extractor`,
  > then a per-draft accept/dismiss review list that commits each accepted draft through the ordinary
  > `CreateItem` path. v1 is **Android-only** (the on-device shacl floor is Android-only) and **flat-create**
  > (inter-draft `parentId`/`children`/`nextTaskId` relations are dropped with a user-visible note; a
  > `parentId` referencing an existing Item is kept). The plan-proposal surface is still as described.
  >
  > **Follow-on (async redesign / Inbox).** The brain-dump review surface moved from the inline overlay
  > list above to a persistent **Inbox** Destination (ADR-0015 Inbox amendment): the redesign transcribes
  > and extracts in the **background**, so drafts outlive the capture overlay and need a durable,
  > state-preserving home. With that move, the **capture overlay is now a plain recorder** (record → Stop →
  > hand the WAV to the background `BrainDumpWorker` → a "transcribing in the background" confirmation →
  > dismiss): the streaming dictation + inline `Extractor` + per-draft accept/dismiss review described in the
  > amendment above are **superseded for brain dump** — only the worker transcribes/extracts now, and the
  > overlay neither streams nor creates (the New-form per-field dictation still streams `listen()`). The
  > accept/dismiss flow and the propose-only `CreateItem` commit are unchanged — they relocated to the Inbox.
  > Optionally retaining the recording as a Task attachment is #211 (on pluggable offline-first attachment
  > storage #210).

**Considered and rejected.** *BYO user API key* (not in v1 — remains additive later as another
engine-catalog entry). *Backend agent endpoints* (duplicates agent
logic in two languages the moment a local tier exists). *Server-fetched prompts* (a versioned
cross-repo prompt contract isn't warranted yet). *Write-tool agent in v1* (guardrail design without a
v1 need — both flows are propose-then-confirm). *No tools at all* (too weak for references to distant
existing Items in the person's graph; local read-only lookup preserves the propose-only safety boundary).
*Strict on-device only* (no iOS path, weak extraction quality; human review already bounds model risk).
*OpenAI-compatible facade on the relay* (the
translation layer drops provider-native structured output — the capability extraction depends on
most).

**Consequences.** The backend repo (`Kyle-Falconer/Deferno`) gains service-side prerequisites: the
relay endpoint and entitlement exposure. The accept paths need **no new
write plumbing** — `CreateItem` and the three plan Commands already exist and route through the
executor; the new client work is `core/agent` itself plus the two review surfaces. `core/agent` sits
under the merged shared-core coverage gate, so the Koog boundary must be isolated behind a fake-able
engine interface. Any service-side accounting happens at the relay, preserving ADR-0009's
no-client-analytics stance.

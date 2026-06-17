# OS-intent integration: caller-categorized capture over the Command registry, behavioral kind-derivation, propose-only untouched

**Context.** ADR-0007 named the shared **[[Command registry]]** the single binding surface for "OS
intents (Android App Actions, iOS App Intents / Siri)," and CONTEXT.md added the **[[OS intent]]** term,
but nothing was built. The goal now: let a person reach Deferno through the **platform's own assistant**
— "hey google **add** take out the trash every Tuesday" (capture) and "what do I have **to do today**?"
(read) — across Google Assistant / Gemini and (later) Siri, plus the **MCP-speaking cloud agents**
(Claude, …) the existing Deferno MCP server already serves.

Three standing constraints shape it. **Propose-only (ADR-0027):** Deferno's own **[[Agent]]** never
commits — it proposes, the person accepts. **Privacy (ADR-0009):** nothing goes off-device silently;
selecting an off-device path *is* the consent. **The four [[Item kind]]s are equal** (Task / Habit /
Chore / Event), and the create payloads for all four already exist (`CreateItem.Payload`), but the
kind-aware backend surface is partly in flux / blocked (#231) — so the assistant edge must not bind to
volatile field names.

The pivotal realization: **smart triage into the four kinds is inference**, and Deferno is the wrong
place to do it. The [[Extractor]] / on-device floor emit **Tasks only** (`DraftTask` carries no `kind`);
a good four-kind classifier needs an LLM tier, and the only one in the catalog is the **Deferno cloud
relay** — premium, entitlement hardcoded `false`, relay not yet deployed (Kyle-Falconer/Deferno#345).
But the *callers* — Gemini, Siri + Apple Intelligence, MCP agents — **are themselves LLMs that already
categorize**: the MCP server already offloads it (an agent picks `create_chore` vs `create_task`). So
push the inference to the caller and Deferno needs none.

**Decision.**

- **OS intents bind to the existing Command registry; Deferno runs no inference on this path.** The
  caller does speech recognition + NLU **off-Deferno**; Deferno exposes a typed tool / intent surface
  and maps the result to a [[Command]] by its stable `CommandId`. Because the inference is the
  **caller's**, ADR-0027's propose-only boundary (which governs *Deferno's* [[Agent]]) is **not
  crossed** — an OS intent is a peer input modality to keyboard / context-menu / drag, and like them it
  **commits**.

- **Capture is caller-categorized via a behavioral schema — never Deferno's kind names.** The tool
  exposes orthogonal, jargon-free questions the caller answers from world knowledge: *occurs at a set
  time you attend?* → **Event**; else *repeats?* `no` → **Task**; else *if its time passes undone, does
  it carry forward or lapse?* carries-forward → **Chore**, lapses → **Habit**. Deferno **derives the
  [[Item kind]]** and builds the kind-specific `CreateItem.Payload`. The caller needs no Deferno
  taxonomy; the fuzzy Habit-vs-Chore split reduces to a *universal* obligation-vs-aspiration judgment;
  and the contract is **behavior-defined, so it survives the backend API churn** that field-name-based
  schemas would not.

- **Caller-categorized capture commits directly; the [[Inbox]] is a fallback, not the path.** Structured
  input ⇒ nothing for Deferno to process ⇒ `CreateItem`, offline-first (optimistic local apply + outbox
  enqueue), an honest **"queued"** result — no propose-only review. This makes the OS-assistant path
  consistent with the MCP server (which already commits). The Inbox / Extractor remain the route **only**
  for a future low-capability caller that hands **raw text** Deferno itself must classify.

- **Mechanism by flow, sequenced.** Read / navigation → **App Actions** deep-link (mature, every
  Assistant device, no Gemini gating) — ships first. Create-with-triage → **structured function call**:
  the MCP server already serves cloud agents; **App Functions** (Android) / **App Intents** (iOS) are the
  on-device path. App Functions is what *enables* assistant-side triage — App Actions BIIs pre-classify
  and slot-strip the full utterance, so they cannot — and it is gated by Google's alpha / device rollout.
  v1 ships App Actions; App Functions follows on the **same** Command seam (additive, not rework).

- **Read defaults to navigation; spoken content is explicit opt-in.** v1 read = deep-link to the
  **[[Plan]]** (App Actions cannot speak dynamic content, and no task content leaves the device). A
  future App Function that reads the plan **aloud** pipes task titles into the assistant vendor's
  pipeline → gated behind an opt-in **[[App setting]]**, off by default, mirroring the
  [[Inference engine]] opt-in (ADR-0027 / 0009): *selecting it is the consent.*

- **Scope + targeting (v1).** Bind only **create** and **open Plan**. No voice command that targets an
  *existing* item (complete / rename / add-to-plan) — those need spoken-title → item resolution
  ([[Reference lookup]] + "which one?" disambiguation), deferred. **Active-Account-only** (no
  per-utterance account picking); signed-out fails gracefully (open the Auth shell / speak "sign in to
  Deferno first"), never a silent drop. **Android-first**; iOS via App Intents on the same seam;
  **Alexa / Bixby out** (a separate cloud skill + OAuth account-linking — cost unjustified now).

**Considered and rejected.**

- ***Deferno's own [[Extractor]] triages the utterance*** (the obvious first design, and where this
  grilling started): the Extractor / floor emit Tasks only, a real four-kind classifier needs an LLM
  tier, and the cloud relay is premium + undeployed (#345) — gating the feature behind Deferno inference
  the callers already provide for free. Push inference to the caller instead.
- ***Expose a `kind: ItemKind` enum to the caller*** (mirror the MCP per-kind tools on-device): leans on
  a general assistant grasping Deferno's fine Habit-vs-Chore taxonomy — brittle. The behavioral schema is
  taxonomy-free and churn-proof. (The MCP server keeps its per-kind tools: a deliberate, supervised
  session picking a tool is fine; the ambient-device schema is the one that must avoid jargon.)
- ***Land caller-categorized captures in the Inbox for review*** (the earlier choice here, made when
  Deferno was to do the triage): once the caller supplies categorized structured input there is nothing
  to defer, and a review step adds friction to an ambient, eyes-free gesture. Inbox stays the raw-text
  fallback.
- ***App-Functions-only now, or a self-hosted foreground Service for background capture*** (the
  Spotify/Pandora pattern the brief raised): App Functions is alpha / Gemini-gated → ships nothing
  broadly today; the background-without-foreground media model is a **privileged media path**
  (`MediaSession`), not available to a custom domain, and a persistent foreground-service notification is
  disproportionate for a sub-second *transaction* (media has a *session*; "add milk" does not).
- ***Speak the plan by default***: routes task content off-device silently — violates ADR-0009. Opt-in
  instead.

**Consequences.** No new write plumbing — `CreateItem` + the four kind payloads already exist; the new
work is the **per-platform binding layers** (App Actions `shortcuts.xml`; App Functions; App Intents) plus
a `behavioral-fields → ItemKind` **derivation in the core** (defined and tested once, like `CommandKind`),
plus the read opt-in App setting. The **behavioral-schema contract becomes a public surface** external
assistants bind to — versioned and stable like `CommandId`; changing a discriminator is a breaking change.
The Habit/Chore derivation depends on the backend's authoritative kind semantics (in flux / backend-owned,
#231) — the *edge* is stable, but the field→kind **mapping must track the backend ADRs**. And read-aloud,
when built, is the **first sanctioned route for task content to leave the device** — a new privacy seam,
honored by explicit opt-in.

> **Amendment (MCP parity).** The Deferno MCP server (`Circuit-Stitch/defernowork-mcp`) **adopts the same
> behavioral-discriminator capture + deterministic kind-derivation**, so the input style is identical
> regardless of source (OS assistant, iOS App Intents, or MCP agent) — "the same deterministic input,
> whatever the caller." This **supersedes** the parenthetical in the rejected "`kind: ItemKind` enum"
> item above ("the MCP server keeps its per-kind tools"): the per-kind `create_*` tools are realigned
> behind a single behavioral capture tool (deprecated or kept only as explicit-kind low-level escapes).
> Consequence: the field→kind derivation tree is now a **cross-repo shared contract** — implemented in the
> client core (this repo) *and* in `defernowork-mcp` — and the two must be kept **in lockstep** (the tree
> is the spec; drift is a correctness bug). The backend remains the authority for the kind semantics the
> tree encodes (#231).

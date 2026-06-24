# The client never calls third-party service APIs directly — the Deferno backend mediates every external integration

**Context.** A third-party integration on the Deferno backend (a GitHub issue sync) once exhausted that
provider's Search API rate budget by fanning a per-repo loop into many account-wide search calls. The
fix was entirely server-side, and the KMP client was never implicated — it holds no third-party
credentials and makes no third-party call — so "could this reach KMP clients?" had a clean *no*. But
that property is currently incidental rather than decided: nothing stops a future feature from adding a
"sync my issues" button that calls a third-party API straight from the device, which would scatter that
provider's rate budget, retries, and credentials across every install with no central place to fix it.
Three standing constraints already point the same way and want to be stated as one rule:
the client is Apache-2.0 open source (ADR-0020), so no third-party provider secret can ship in the
binary; hosted LLM inference is therefore forced behind a Deferno-operated relay rather than a direct
provider call (ADR-0027); and writes are offline-first through the outbox to the backend, not direct
mutations of a remote service (ADR-0034).

**Decision.**

- **The client's only data-plane peer is the Deferno backend API.** All access to a third-party
  *service* (its data API, on a user's behalf, authenticated with that service's credentials —
  GitHub, calendar providers, future integrations) is **owned by the backend**. The client asks the
  backend; the backend calls the third party, holds its tokens/installations, owns its rate budget,
  retry/backoff, and pagination, and returns domain Items. A new integration adds **backend endpoints**,
  never a client-side third-party client.
- **No third-party credential or provider key lives on the device.** This already holds for inference
  (the relay, ADR-0027) and follows from the OSS license (ADR-0020); it is now general. The only
  secret the client holds is the user's own Deferno auth (PAT, ADR-0023; PKCE, ADR-0026).
- **Hosted inference is the shape of the rule, not an exception to it.** Even the client-orchestrated
  agent (ADR-0027) reaches Anthropic only through the Deferno relay. Any future "client talks to a
  hosted thing" follows that precedent: a thin Deferno-operated passthrough, not a direct vendor call.
- **Narrow, explicit carve-outs — these are *not* service-data API calls and stay client-side under
  their own ADRs:** fetching static release/asset artifacts over HTTPS (desktop self-update from GitHub
  Releases, ADR-0021; Whisper model delivery, ADR-0019), opening a public web URL in the system browser
  (the Releases *page* link), the OS keyring (ADR-0024), and fully on-device work (speech, on-device
  inference, ADR-0037). The line: **anything that reads or writes a user's data in a third-party service
  goes through the backend; static-asset fetch, browser hand-off, and on-device compute do not.**

**Considered and rejected.** *Client-side third-party SDKs/clients (a GitHub client in `core/network`)*
— duplicates auth, token storage, and rate-budget logic on every device, leaks the OSS-incompatible
secret problem, and reproduces the motivating fan-out with no central place to fix it.
*Per-integration relays mirroring the inference relay* — over-built; the backend already owns the
domain and its data store, so integrations are ordinary backend endpoints, not passthroughs (the relay
exists only because the *agent logic* is deliberately client-side, ADR-0027). *Leaving the property
implicit* — it held by luck once already; a one-line rule is cheaper than rediscovering it per feature.

**Consequences.** The KMP client is structurally insulated from third-party rate limits, outages, and
auth-format churn — those surface (if at all) as ordinary backend error envelopes the client already
handles (`5xx`/`429`/`408`, see `OutboxRequestSender`). New integrations are gated on backend work, not
client work, which keeps integration logic in one language and one place. Reviewers get a bright line:
a PR adding a `core/network` client (or any on-device fetch) against a third-party *service data* API
contradicts this ADR and should instead define a backend endpoint. The motivating incident above is the
shape this prevents: a per-item loop on the device fanning into a third-party rate budget the client
can't centrally cap. The carve-outs above are exhaustive;
extending them is itself an ADR amendment.

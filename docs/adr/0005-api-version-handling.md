# Client-side API version handling

**Context.** Every Deferno response is wrapped in `Envelope<T>` = `{ version, data }`. The API will
evolve over time and the same backend serves clients that update independently (corp scale). We need
the client to tolerate that evolution rather than break on it.

**Decision.**
- **Contract semantics (a):** the envelope `version` is a **breaking-contract counter** — additive,
  backward-compatible changes do *not* bump it; only breaking changes do. (Backend adopts this +
  declares `0.2` honestly + adds a version-declaration header — see
  [Kyle-Falconer/Deferno#300](https://github.com/Kyle-Falconer/Deferno/issues/300).)
- **Tolerant reader:** kotlinx.serialization with `ignoreUnknownKeys`, `coerceInputValues`, and
  explicit defaults, so additive changes never break parsing.
- **Versioned-adapter layer** in `core:network`: a decoder reads `envelope.version`, checks it against
  a **supported window `[min..max]`**, and routes `data` through the version-appropriate DTO→domain
  mapper. It ships **present-but-empty** (single pass-through) and earns its first adapter only at the
  next breaking bump.
- **Floor = 0.1 (amended 2026-06-06):** the supported window opens at `0.1` — the version the live
  backend actually serves (staging confirmed: `GET /auth/me` → `{"version":"0.1", …}`). `min = max =
  0.1` today; widen to include `0.2` once
  [Kyle-Falconer/Deferno#300](https://github.com/Kyle-Falconer/Deferno/issues/300) lands and
  declares `0.2` honestly. The floor is a single bumpable constant.
  _Superseded reasoning:_ this ADR originally set the floor at `0.2` ("no client ever shipped on
  0.1"). That held for *clients*, but the *server* serves `0.1` in dev/staging today and #300 has
  not landed — so a `0.2` gate would make the client's own envelope reader reject every real
  response (the tracer would pass auth, then fail the version check). We develop against the version
  that exists and widen the window as the backend advances; capture **0.1** fixtures, not
  aspirational `0.2` ones.
- **Out-of-window policy:** server version **above** max (an unknown breaking major) ⇒ **force-upgrade
  gate** ("update required"), since a breaking shape can't be safely parsed; below min ⇒ degrade/refuse.
  Unknown versions are logged so we know when to ship an adapter.
- **Declaration header:** the client sends its supported version (`X-Deferno-Api-Version`) once #300
  lands, enabling clean `406`s and future server-side compatible serving.

**Considered & rejected.** Tolerant-reader-only (can't survive a breaking change); per-version DTOs
for *every* change (needless ceremony for additive changes); full semver on the envelope (more
expressive but unnecessary once additive changes simply don't bump).

**Consequences.** The network boundary always maps DTOs → a single canonical domain model, so the
rest of the app never sees a wire version. A breaking API bump is a localized change: add a DTO set +
mapper for the new version and widen the window.

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
- **Floor = 0.2:** no client ever shipped on 0.1, so v1 targets `0.2` as min/max; **no `0.1→0.2`
  back-compat** is carried.
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

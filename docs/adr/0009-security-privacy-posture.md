# Security & privacy posture

**Context.** Privacy and security are a top priority. This records the v1 posture and the deliberate
deferrals. Already decided elsewhere: per-Account **encrypted DB** (SQLCipher), **secure token vault**
(Keystore/Keychain, ADR-0002), **browser-only credentials** (ADR-0003 / #299), hard Account isolation.

**Decision.**
- **Transport:** TLS enforced, cleartext blocked (Android cleartext off / iOS ATS). **No certificate
  pinning in v1** — deferred for operational simplicity (a botched pin rotation can brick installed
  clients); revisit if the threat model warrants.
- **Biometric app-lock:** optional, **off by default**, per-Account — gates app open and
  sensitive-Account switch (AndroidX Biometric / iOS LocalAuthentication).
- **Screenshot / recents protection:** a **user setting, default off** — runtime `FLAG_SECURE`
  (Android) + conditional app-switcher blur (iOS).
- **Telemetry:** **no third-party client analytics SDK.** Product behavior is understood via
  **server-side analytics** — with the accepted limitation that, being offline-first, the server only
  observes **mutations at sync time**, never local engagement/navigation. **Crash reporting via
  platform built-ins** (Google Play Console vitals / Apple MetricKit + Organizer); no third-party
  crash SDK. (PostHog stays webui-only.)
- **Data portability:** user-facing **export/import** supported via the Deferno API
  (`export_data`/`import_data`, webui parity).
- **OS backup/transfer:** the local encrypted DB + **device-bound, non-exportable** keys are a **cache
  excluded from OS backup** in v1 — a new device re-authenticates and **re-syncs from the server**
  (the source of truth). OS-level backup/transfer is **reserved**: if added it must retain encryption
  across devices via a **user-passphrase-derived key** (since device-bound keys can't leave the
  device) — not precluded.
- **Lifecycle:** secure-wipe (DB + token + keys) on Account removal — **and revoke the PAT
  server-side** (`DELETE /auth/tokens/{id}`, ADR-0012); **retain** the encrypted cache on a mere
  `401` (re-auth, don't wipe); never log tokens/PII.

**Considered & rejected.** Certificate pinning in v1 (ops burden > benefit for now); third-party
analytics/crash SDKs (privacy).

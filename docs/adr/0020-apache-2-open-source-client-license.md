# Open-source client under Apache-2.0 — the moat is the service, not the client

**Context.** We are open-sourcing the Deferno **client** while the **backend stays proprietary and
author-operated**. A license audit of the full dependency graph confirmed **nothing forces our hand**:
every shipped dependency is **Apache-2.0, MIT, or BSD-3-Clause** (Compose Multiplatform, Decompose,
kotlin-inject, kotlinx-*, Ktor, SQLDelight, multiplatform-settings; whisper.cpp + ggml MIT; SQLCipher
BSD-3; java-keyring BSD-3). The two copyleft-looking deps are non-issues: **JUnit (EPL-1.0)** is
test-scope only and never ships, and the **JetBrains Runtime (GPLv2)** bundled into the desktop
installers (ADR-0019) is covered by its **Classpath Exception** — a bundled JRE does not make the app
GPL. So the choice is **strategic, not forced**: what license best serves an open client over a closed
service.

**Decision.** **Apache-2.0** for the whole first-party client (Android + Desktop + iOS).

- **The moat is the hosted service + accumulated data + network effects — not the client source.**
  Open-sourcing a thin client costs essentially nothing competitively, so the right trade is the one
  that **maximizes adoption, trust, and contributions**. Apache-2.0 is the **de-facto ecosystem
  default** for this exact stack (Kotlin itself, coroutines, Ktor, Decompose, kotlin-inject,
  SQLDelight, AndroidX/AOSP are all Apache-2.0) — lowest-friction, zero surprise, drive-by-PR friendly.
- **Chosen over MIT** for the **explicit patent grant + patent-retaliation termination** and the
  **NOTICE/trademark hygiene** (§6 grants no trademark rights — a forker must rebrand off **Deferno**),
  at no contributor cost.
- **The cleanest real-world precedent matches us:** DuckDuckGo's Android app — open client, proprietary
  backend — is Apache-2.0; community clients to closed SaaS skew permissive (librespot MIT, Spotube
  BSD). The copyleft examples (Signal, Mastodon, Bitwarden, Nextcloud) all **also open-source their
  servers** — not our situation.

**Forward path / obligations the release must carry.** Apache-2.0 satisfies no attribution
automatically — the open-source release (and the desktop installers in particular) must ship:

- a root **LICENSE** (Apache-2.0 text) and **NOTICE** (also the home for the **Deferno** trademark +
  flame-branding reservation), neither of which exists yet;
- a **THIRD-PARTY-LICENSES** file aggregating: MIT for **whisper.cpp + ggml** (the "ggml authors")
  **and the bundled model weights** (ADR-0019), BSD-3-Clause for **SQLCipher** and **java-keyring**,
  **SIL OFL-1.1** for the bundled **IBM Plex** fonts (`core/designsystem`), and EPL-1.0 for JUnit (test);
- on **desktop only**, the **JBR GPLv2 + Classpath-Exception** license text **plus a corresponding-source
  pointer**, wired into `nativeDistributions` packaging so jpackage ships it with the bundled runtime;
- **IBM Plex's "Plex" Reserved Font Name** must be left intact (the font files can't be renamed);
- **CONTRIBUTING with a DCO sign-off** (inbound = outbound) so external PRs are unambiguously Apache-2.0.

> **Correction (2026-06-08, #100):** the audit above first labeled **java-keyring** MIT; its bundled
> source is in fact **BSD-3-Clause** (`Copyright © 2019, Java Keyring`). Still permissive, so the
> Apache-2.0 conclusion is unchanged — the shipped `THIRD-PARTY-LICENSES` records it under BSD-3-Clause.

**Consequences.** Anyone may fork and even resell the client — accepted, because the client was never
the asset. Relicensing later (e.g. to source-available) would require **contributor consent** once
outside contributions land; a DCO keeps provenance clean but does not grant relicensing rights (no CLA
chosen — deliberately, to keep contribution frictionless).

**Considered & rejected.**

- **MIT** — equivalent permissive fit, but **no patent grant** and no trademark/NOTICE hook. Apache-2.0
  dominates it at no added cost.
- **AGPL-3.0 "to protect the SaaS"** — the classic cargo-cult mistake here. AGPL §13's network-copyleft
  only fires when **users interact with the *licensed work* remotely over a network**; for a
  **locally-run client** that never happens, so it yields **zero** backend protection while inflicting
  maximal contributor / ecosystem / App-Store cost. The backend stays proprietary regardless of the
  client's license. Recorded so this is not re-proposed.
- **GPLv3 / MPL-2.0** — solve the wrong half (closed-source *redistribution* of the client) and still
  permit a competing-backend fork; copyleft is not the library-ecosystem norm and adds friction for no
  benefit we want.
- **Source-available (FSL-1.1-Apache-2.0 / BSL / Elastic v2)** — the *only* tools that actually bar
  "fork the client onto a competing backend / resell it," but **not OSI open source** (FSL only after a
  2-year per-release conversion). Rejected because that competitive-fork fear is **hypothetical**, not a
  present concern — the FSL fallback is on record should that judgment change. Note even source-available
  can't stop someone writing **their own** client against the API.

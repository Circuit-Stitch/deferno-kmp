# Architecture Decision Records

One decision per record, immutable once accepted. Read `TEMPLATE.md` before adding one, and read
`../agents/domain.md` for how these records are meant to be consumed.

**This index is the number-allocation register.** Claim the next free number by adding its row here
in the same commit as the new record — that is what stops a second collision like the one where two
records both claimed `0034` (resolved 2026-07-29: the Item-tree record became ADR-0049).

**Status vocabulary.** `Accepted` · `Amended by ADR-NNNN` (part of it was replaced — the record
still holds live rules) · `Superseded by ADR-NNNN` (wholly replaced) · `Historical` (the subject was
wholly replaced; kept only to reconstruct what was believed) · `Deferred`.

A record's *argument* is immutable — Context, Considered & rejected, and the original Decision text
record what was believed and why. Status, pointers, citations and numbering are mutable; a reversed
Decision bullet is annotated in place and left legible. `AdrCorpusParityTest` enforces the
mechanical half of this on the `check` path.

| # | Record | Status | Date |
|---|---|---|---|
| [0001](0001-offline-first-local-source-of-truth-outbox.md) | Offline-first: local source of truth + outbox, last-writer-wins | Accepted | 2026-06-05 |
| [0002](0002-account-hard-isolation-org-filter.md) | Account is the hard isolation boundary; Org is an in-account filter | Accepted | 2026-06-05 |
| [0003](0003-kmp-shared-presentation-native-ui.md) | KMP shared core through presentation; native UI per platform | Amended by ADR-0029 | 2026-06-05 |
| [0004](0004-module-structure-nia-hybrid.md) | Module structure: NIA-style hybrid, co-located Android Views, convention plugins | Amended by ADR-0028 | 2026-06-05 |
| [0005](0005-api-version-handling.md) | Client-side API version handling | Accepted | 2026-06-05 |
| [0006](0006-testing-strategy.md) | Testing strategy & coverage policy | Accepted | 2026-06-05 |
| [0007](0007-adaptive-multipane-command-registry.md) | Adaptive multi-pane UI + shared command registry (Android, iPad, desktop) | Amended by ADR-0049 | 2026-06-05 |
| [0008](0008-multi-window-stage-manager-deferred.md) | Multi-window / Stage Manager: deferred past v1, but not precluded | Deferred | 2026-06-05 |
| [0009](0009-security-privacy-posture.md) | Security & privacy posture | Accepted | 2026-06-05 |
| [0010](0010-client-design-system-touch-first.md) | Client design system: mobile/touch-first, independent of the webui pattern library | Accepted | 2026-06-05 |
| [0011](0011-api-dto-modelling-condense-at-edge.md) | API DTO modelling: faithful flat wire, condensed domain | Accepted | 2026-06-06 |
| [0012](0012-native-auth-pat-credential.md) | Native auth: OAuth is the bootstrap, a personal access token is the credential | Accepted | 2026-06-06 |
| [0013](0013-navigation-shell-destinations.md) | Navigation shell: Auth vs Main, and a Destination graph with multiple back stacks | Accepted | 2026-06-06 |
| [0014](0014-di-scope-placement-data-layer.md) | DI scope placement: the per-Account data layer is AccountScope, not AppScope | Accepted | 2026-06-07 |
| [0015](0015-v1-navigation-surface-deferred-destinations.md) | v1 navigation surface: buildable Destinations now, backend-blocked surfaces deferred | Amended by ADR-0040 | 2026-06-07 |
| [0016](0016-create-online-only-v1.md) | v1 create is online-only; edits stay offline-first | Amended by ADR-0034 | 2026-06-07 |
| [0017](0017-shared-app-shell-module-per-platform-views.md) | The app shell is a shared KMP module; its Views stay per-platform | Accepted | 2026-06-08 |
| [0018](0018-on-device-stt-portable-whisper-baseline-native-fast-paths.md) | On-device speech-to-text: a portable whisper baseline with opportunistic native fast paths | Accepted | 2026-06-08 |
| [0019](0019-whisper-model-distribution-platform-asset-delivery.md) | Whisper model distribution: platform asset delivery, not bundled-in-binary, not self-hosted | Accepted | 2026-06-08 |
| [0020](0020-apache-2-open-source-client-license.md) | Open-source client under Apache-2.0 — the moat is the service, not the client | Accepted | 2026-06-08 |
| [0021](0021-desktop-release-and-self-update-conveyor-github-releases.md) | Desktop release + self-update: Conveyor over jpackage-DIY, off public GitHub Releases | Accepted | 2026-06-08 |
| [0022](0022-sqldelight-versioned-migrations.md) | SQLDelight schema migrations: versioned, immutable, append-only | Accepted | 2026-06-08 |
| [0023](0023-v1-signin-validate-and-store-pasted-pat.md) | v1 sign-in: validate a pasted PAT against `/auth/me`, then store it | Amended by ADR-0026 | 2026-06-08 |
| [0024](0024-macos-native-capabilities-launchd-swift-sidecar.md) | Native macOS capabilities via a launchd-activated Swift sidecar | Amended by ADR-0029 | 2026-06-09 |
| [0025](0025-multi-os-sidecar-substrate-cross-os-transport.md) | Multi-OS native-sidecar substrate + cross-OS transport | Accepted | 2026-06-09 |
| [0026](0026-native-browser-pkce-signin.md) | Native sign-in: system-browser OAuth Authorization Code + PKCE, minting a per-device PAT | Amended by ADR-0033 | 2026-06-10 |
| [0027](0027-client-koog-agent-hosted-relay-propose-only.md) | Client-side agent: Koog in the shared core, hosted inference behind a thin Deferno relay, propose-only v1 | Amended by ADR-0037, ADR-0052 | 2026-06-10 |
| [0028](0028-per-platform-view-bodies-duplicated.md) | Per-platform feature View bodies are intentionally duplicated, not collapsed into commonMain | Accepted | 2026-06-12 |
| [0029](0029-native-macos-swiftui-app-real-macos-target.md) | Native macOS app: a real Kotlin/Native `macosArm64` target, SwiftUI Views, in-process capabilities | Amended by ADR-0033 | 2026-06-13 |
| [0030](0030-plan-task-detail-tier-3-drill-down-not-overlay.md) | Plan task detail is a tier-3 drill-down, not a shell overlay | Accepted | 2026-06-15 |
| [0031](0031-one-shell-computed-chromespec-top-bar.md) | One shell-computed `ChromeSpec` top bar | Amended by ADR-0044 | 2026-06-15 |
| [0032](0032-top-bar-create-affordances-stay-overlay-intents.md) | Top-bar create affordances stay overlay/navigation intents, not Command-registry commands | Amended by ADR-0044 | 2026-06-15 |
| [0033](0033-macos-detached-task-detail-windows.md) | Detached, navigable per-task detail windows on macOS | Accepted | 2026-06-15 |
| [0034](0034-offline-first-create-client-supplied-uuids.md) | Offline-first Item create with client-supplied UUIDs (supersedes the create half of ADR-0016) | Accepted | 2026-06-15 |
| [0035](0035-android-edge-to-edge-component-owned-insets.md) | Android draws edge-to-edge; each component owns its window insets | Accepted | 2026-06-16 |
| [0036](0036-os-intent-caller-categorized-capture.md) | OS-intent integration: caller-categorized capture over the Command registry, behavioral kind-derivation, propose-only untouched | Accepted | 2026-06-17 |
| [0037](0037-ios-on-device-brain-dump-apple-speech-foundation-models.md) | iOS on-device brain dump: Apple Speech transcription + Foundation Models extraction, with salvage-draft fallback | Accepted | 2026-06-22 |
| [0038](0038-server-mediates-all-external-api-calls.md) | The client never calls third-party service APIs directly — the Deferno backend mediates every external integration | Accepted | 2026-06-24 |
| [0039](0039-coverage-badge-one-merged-codecov-display-only.md) | Coverage is surfaced as one merged-core badge via Codecov; Codecov is display-only, never a gate | Accepted | 2026-06-24 |
| [0040](0040-ios-assistant-server-mediated-conversational-client.md) | iOS Assistant: a thin client over the backend's server-mediated conversational AI, distinct from the propose-only Agent | Accepted | 2026-06-25 |
| [0041](0041-data-portability-rest-envelope-backup-file.md) | Data portability: a REST-envelope Backup file, offline on-device export + server Full extract, id-preserving restore | Accepted | 2026-06-28 |
| [0042](0042-offline-first-search-cached-attachment-rollup.md) | Offline-first global Search over the cache + a cached attachment rollup | Accepted | 2026-06-29 |
| [0043](0043-offline-first-comments-cached-item-history.md) | Offline-first Task comments + a cached server item-history feed | Accepted | 2026-07-06 |
| [0044](0044-task-detail-connected-parent-tabbed-journey-status.md) | Task detail: connected-parent header, tabbed sections, read-only journey status | Amended by ADR-0046 | 2026-07-09 |
| [0045](0045-task-detail-android-fab-bottom-sheet-add-actions.md) | Task detail add-actions: an Android FAB + ModalBottomSheet, not the expressive FAB menu | Accepted | 2026-07-10 |
| [0046](0046-task-detail-trail-merged-enriched-history.md) | Task detail: one reverse-chronological Trail (comments + enriched read-only history), not two tabs | Accepted | 2026-07-10 |
| [0047](0047-environment-build-variants-coexisting-dogfood-installs.md) | Environment as a build variant: coexisting, isolated prod/staging installs for dogfooding | Accepted | 2026-07-15 |
| [0048](0048-activity-ledger-optimistic-cache-of-server-ledger.md) | The Activity ledger is an optimistic cache of the server's, merged by a client-minted `entry_id` | Accepted | 2026-07-25 |
| [0049](0049-tasks-item-tree-modal-move.md) | Tasks Destination renders the Item decomposition tree; modal button-based Move over `/items` | Accepted | 2026-06-15 |
| [0050](0050-task-dependency-edges-server-derived-blocked-flags.md) | Task dependency edges are client-writable; the Blocked and blocker flags are server-derived | Accepted | 2026-06-26 |
| [0051](0051-client-when-decomposition-day-plus-optional-clock.md) | WHEN decomposes to day + optional clock; all-day is derived, and the offline-first client normalizes locally | Accepted | 2026-08-02 |
| [0052](0052-soft-target-date-and-stored-priority-bucket.md) | The soft Target date and the stored Priority bucket are read on all four kinds and written on Task | Accepted | 2026-08-02 |
| [0053](0053-client-reproduces-occurrence-grid-offline.md) | The client reproduces the Occurrence grid offline, and the server ships the expansion inputs it needs | Accepted | 2026-08-03 |
| [0054](0054-item-projection-carries-series-expansion-inputs.md) | The Item projection carries the series expansion inputs, so a tree row expands its grid cold | Accepted | 2026-08-05 |
| [0055](0055-item-is-core-plus-sparse-plugin-list.md) | An Item is a Core plus a sparse plugin list, cut along eight meaning families | Accepted | 2026-08-11 |
| [0056](0056-four-kind-wire-stays-recipe-round-trip-gates.md) | The four-kind wire stays until the backend lands, and recipe round-trip is the gate | Accepted | 2026-08-11 |
| [0057](0057-unsendable-families-device-local-wiped-at-cutover.md) | Families the wire cannot carry persist device-locally and are wiped at cutover | Accepted | 2026-08-11 |

_57 records. Next free number: **0058**._

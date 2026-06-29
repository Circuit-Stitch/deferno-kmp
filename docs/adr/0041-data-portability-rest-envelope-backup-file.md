# Data portability: a REST-envelope Backup file, offline on-device export + server Full extract, id-preserving restore

**Status:** accepted (makes the deferred export/import real, starting on iOS; builds on ADR-0005 envelope
versioning, ADR-0034 client-supplied-id offline create, ADR-0001 outbox, ADR-0038 server-mediated
external work).

**Context.** "Export or import your data" has been a stub: a Settings button that deep-links to the
**web app** (`SettingsView.swift`, `SettingsComponent.onOpenDataExportImport`) because "there is no client
REST endpoint at envelope v0.1." There is **no bulk export/import endpoint** in `contracts/openapi-0.1.json`
and none planned short-term. We want to make it real — **iOS first** — and it must be **offline-first**
(ADR-0001) and **compatible with the web API**. Two facts shape the design: (1) the backend honors
**client-supplied item ids** and dedupes on them (ADR-0034), so re-creating an item with its original id is
idempotent; (2) the **local DB is a partial mirror** — items carry full detail only once **hydrated**
(`TaskEntity.hydration_state`), and **comments, server history (`actions[]`), and backend-hosted attachment
metadata are never cached locally** (no comment/history tables; `LocalAttachmentEntity` holds only
[[On-device attachment]]s). So a purely offline export *cannot* be full-fidelity — only the server holds
everything.

**Decision.**

- **One file format — the [[Backup file]].** A **zip**: `items.json` is the REST response envelope
  `{ version, data }` (ADR-0005) carrying the **same snake-case DTO shapes** the API's read endpoints emit
  (`data` = an array of cross-kind item detail objects, with comments/history/attachment metadata nested
  per item where present); `attachments/<attachmentId>` holds raw bytes. "Compatible with the web API" is
  satisfied **by construction** — `items.json` *is* the API's own JSON — and the file **carries the
  envelope `version`** so an import can version-gate it. (The entry is named for its content — the items —
  rather than `manifest.json`, which reads as metadata when it is in fact the data.) **Plaintext** (like any "export my data"): the
  person controls the destination; encrypting would break direct web/API readability, the whole point.
- **Two export modes, chosen at export time.**
  - **[[On-device export]] (built now):** a pure-offline snapshot of the **local DB only** — items
    (`description` present only for hydrated items) + [[On-device attachment]] bytes. It **omits** comments,
    history, and backend-hosted attachments (not local). Handed to the iOS share sheet. Always works with no
    network, at the cost of being partial.
  - **[[Full extract]] (deferred — backend-gated):** a **server-side job** the client merely requests,
    which assembles the **complete** Backup file (incl. backend-hosted attachment bytes), stores it in
    object storage, and **emails a time-limited download link** (~30 days). Server-mediated (ADR-0038).
    Surfaced now as "coming soon"; needs a new backend endpoint (a `Kyle-Falconer/Deferno` issue).
- **[[Import]] is an id-preserving restore/merge (built now).** Parse the zip; for each item, optimistic
  local upsert + enqueue a **create with its original id** through the existing offline outbox. Idempotent:
  re-importing is a no-op (backend dedupes on id); a collision with an existing-but-different item is
  reconciled **last-writer-wins** (existing behaviour), **not** duplicated and **not** per-conflict review.
  Embedded bytes restore as **local on-device attachments** (never re-uploaded). **History is not restored**
  (the backend owns the audit log). **Comment restore defers** with the Full extract (no on-device file
  carries comments). Items land in the **active account's personal org** (the file's `owner_org_id`/`ref`/
  `sequence` are informational; the create path re-homes — hard isolation is intact because the *person* is
  moving their own data). Version-gated by ADR-0005's window: the file's `version` **above MAX** → force-upgrade
  ("update to import"); **below MIN** → refuse.
- **Engine in shared KMP core; iOS owns only the pickers.** The read-DB → build-zip → parse-zip →
  replay-create logic lives in `core/data`/`core/network` (where the DB, outbox, DTOs, and create path
  already are), so Android/desktop inherit it. iOS contributes only the **share sheet / document picker**,
  bridged across K/N — the same host-concern seam `onOpenDataExportImport` used, which the in-app action
  sheet (Export… / Full backup—soon / Import…) now replaces.
- **Externally-sourced items are excluded from export** (`external` provenance: synced from GitHub/Google/
  etc.) — the external integration re-creates them on sync, so exporting/re-importing them would fight it.

**Considered & rejected.**

- **A custom or CSV format** — loses the free web-API compatibility and the version gate; a zip of the REST
  envelope reuses the existing DTOs and reader wholesale.
- **Single base64-in-JSON file** (no zip) — "one API document," but bloats ~33% and loads all bytes
  (incl. brain-dump audio) into memory; a zip keeps `items.json` pure and streams bytes.
- **Mint-new-ids "copy" import** — breaks round-trip (re-import duplicates) and forces parent/child +
  sequence remapping; id-preserving restore is idempotent for free via ADR-0034.
- **Per-conflict review on import** — a triage queue atop restore; last-writer-wins already exists and is
  enough for v1.
- **Encrypted backup** (password or device/account key) — fights both the web-API-compatibility goal and
  portability (an account-key file can't be read on the web or another device).
- **Hydrate-on-export when online** (fetch comments/history/detail to make the on-device export complete) —
  duplicates what the Full extract does server-side and erodes the clean offline/online split; rejected in
  favour of an honest partial on-device snapshot + a complete server extract.
- **Server-mediated bulk import** — no endpoint exists, and replaying creates through the outbox keeps import
  offline-first and idempotent without one.

**Consequences.** Export/import stops being a web redirect. The on-device path ships independently of the
backend and is fully offline; true full-fidelity backup waits on the Full-extract endpoint. Restore is
resumable and safe to repeat by construction (idempotent creates). The two modes are **deliberately
asymmetric** — a future reader seeing "on-device export omits comments" should look here, not treat it as a
bug: comments/history simply aren't on the device. New backend work is needed for the Full extract; new
outbox mutations (post-comment, attachment) are needed before import can restore comments/attachments to the
server.

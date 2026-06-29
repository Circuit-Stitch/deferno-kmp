# Offline-first global Search over the cache + a cached attachment rollup

**Status:** accepted (#311). Amends ADR-0007's "Search is online-only" note; aligns Search with ADR-0001
(offline-first); builds on ADR-0034 (the `/items` snapshot + per-kind stores).

**Context.** Global Search (#73) was the **one online-only read** left in the client: `SearchComponent`
→ `OfflineTaskRepository.search` → `GET /tasks/search`, a one-shot pull whose results were never cached.
It contradicted ADR-0001 (reads are local `Flow`s; the cache is the source of truth) and simply didn't
work offline. Separately, the local cache held **zero attachment metadata**: the server ships an
`attachments` array on every item in the `/items` snapshot, but the tolerant reader (`DefernoJson`,
`ignoreUnknownKeys`) **dropped it** — so even a local Search had nothing to filter "has attachment" or
sort "by size" on. #311 (spun out of the #211 Storage work) asked for exactly that affordance: "find my
biggest attachments across all items."

**Decision.**

- **Search is offline.** `TaskRepository.search` now runs as a **local read over the four per-kind
  caches** (Task/Habit/Chore/Event — the same stores [[ItemSync]] feeds), filtered + sorted in Kotlin.
  The online `/tasks/search` path is **deleted** (`KtorTaskRemoteSource.search`, `TaskRemoteSource.search`).
  A local read can't fail, so `search` returns a plain `List<SearchHit>` — the `TaskSearchResult.Unavailable`
  / `searchFailed` machinery is gone (a search can't be "unavailable" offline).
  `// ponytail: in-memory filter over the cached item lists; swap to an indexed/FTS SQL query only if a
  personal cache ever gets large enough to matter.`
- **Cross-kind text, Task-scoped structured filters.** #231's kind-agnostic results survive: every kind
  matches free-text (title + description) and labels. The **status**, **date-range**, **has-attachment**
  filters and the **attachment-size** sort are Task-scoped — recurring kinds have no `WorkingState` /
  attachment rollup, so they fall out of those filters and sort last. (No invented
  `DefinitionState`→`WorkingState` mapping; honest and small.)
- **Cache an attachment rollup — no backend work.** The snapshot already carries the per-item
  `attachments` array (each entry has a byte `size`). The DTO→domain mapper now **derives**
  `attachment_count` + `attachment_total_size` from it (instead of dropping it), persisted on `taskEntity`
  (migration 11→12). The issue's "backend dependency" (ship a scalar rollup) is **avoided by construction**.
  **Task-only**, mirroring the 10→11 external-provenance migration: file attachments live on Tasks; the
  recurring kinds carry no rollup yet (upgrade path: extend the columns + mapper if they ever do).
- **Deep-linkable.** `OverlayRoute.Search` gained a `SearchSeed(hasAttachment, sort)` payload; opening with
  a seed pre-applies the filter/sort and runs the search immediately. Settings → Storage gains a "Biggest
  attachments" affordance (`SettingsComponent.Output.OpenBiggestAttachments` → the shell opens Search
  seeded `hasAttachment = true, sort = AttachmentSizeDesc`) — the original #211 ask. iOS + macOS native
  Search render the new "Has attachment" chip + the "Biggest attachments" sort (data-driven) and show a
  per-hit "N files · size" badge; the iOS Settings Storage screen hosts the deep-link (macOS has no Storage
  settings yet).

**Considered & rejected.**

- **FTS5 / a `LIKE` SQL query.** A personal task cache is hundreds–low-thousands of rows; an in-memory
  `contains` filter over the already-observed lists is enough and far less code. Add FTS only if it
  measurably falls short.
- **Wait for a backend count/size rollup field.** Unnecessary — the array is already on the wire; deriving
  client-side ships now and stays fully offline.
- **Attachment metadata on all four kinds.** Recurring items don't carry file attachments today; Task-only
  halves the schema/DTO/mapper surface (same call as external provenance).
- **Keep an online fallback / hybrid search.** Reads are local `Flow`s (ADR-0001); a hybrid re-introduces
  the online failure surface this ADR removes. After a refresh the cache holds everything in the server's
  done-visibility window, which is the offline-first contract.

**Consequences.** Search works with no network and gains attachment filter/sort across all items, with no
backend change. The trade-off — **Search only finds cached items** (everything within the server's
done-visibility window after a refresh); items aged out of that window or never synced won't appear,
whereas the old online search hit the full server index. This is the intended ADR-0001 alignment, not a
regression to fix. New backend work (a true count/size field, or attachments on recurring kinds) can later
widen the rollup, but isn't needed for v1.

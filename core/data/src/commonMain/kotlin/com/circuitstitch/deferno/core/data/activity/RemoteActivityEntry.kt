package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.network.dto.ActivityEntryDto
import kotlin.time.Instant

/**
 * One authoritative entry from the server's Activity ledger, condensed from the wire DTO (#364) — what
 * [ActivityLedgerStore.upsertRemote] merges into the local cache.
 *
 * Distinct from [ActivityEntry], which is the *merged* row the feed renders: this type carries only the
 * server's half, has no local `seq`, and never carries the outbox-derived `target`/`body`/`before` an
 * optimistic row captured. Keeping them separate is what lets the upsert overwrite the authoritative
 * columns while leaving a local row's richer captured diff intact.
 */
data class RemoteActivityEntry(
    val entryId: String,
    val itemId: String,
    val actionKind: ActivityActionKind,
    val actorKind: ActivityActorKind,
    val source: ActivitySource,
    val occurredAt: Instant,
    val observedAt: Instant,
    val provider: String? = null,
    val occurrence: String? = null,
    val seriesId: String? = null,
    val changedFields: List<String> = emptyList(),
    val detail: String? = null,
)

/**
 * Condense a wire entry, or `null` if it is unusable.
 *
 * The only rejection is an **unparseable timestamp**: both instants are load-bearing (one is the sort axis,
 * the other the sync watermark) and a row without them cannot be placed. Everything else degrades — an
 * unknown `action_kind` keeps its raw token via [ActivityActionKind.Other], an unknown `source`/`actor_kind`
 * reads as `Unknown` — because dropping an entry the client merely doesn't *recognise* would silently
 * under-report a forensic stream, which is the one thing this feed must not do.
 *
 * A JSON-`null` `detail` — the server's degradation when it cannot unwrap the org DEK, which it prefers
 * to dropping the row — already reaches here as Kotlin `null`: the shared reader's `explicitNulls = false`
 * collapses a wire null onto the nullable property, so there is one absent-case, not two.
 */
fun ActivityEntryDto.toRemote(): RemoteActivityEntry? {
    val occurred = runCatching { Instant.parse(occurredAt) }.getOrNull() ?: return null
    val observed = runCatching { Instant.parse(observedAt) }.getOrNull() ?: return null
    return RemoteActivityEntry(
        entryId = entryId,
        itemId = itemId,
        actionKind = ActivityActionKind.fromToken(actionKind),
        actorKind = ActivityActorKind.fromToken(actorKind.orEmpty()),
        source = ActivitySource.fromWire(source),
        occurredAt = occurred,
        observedAt = observed,
        provider = provider,
        occurrence = occurrence,
        seriesId = seriesId,
        changedFields = changedFields,
        detail = detail?.toString(),
    )
}

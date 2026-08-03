package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate

/**
 * The outbox [target][Mutation.target] scheme for the occurrence write path (#74, #380) — the
 * firing-level sibling of [CommentTargets], and the single source of truth both the enqueue side (the
 * three [OccurrenceMutation] intents) and every read side (the Activity ledger's verb derivation, the
 * flush-time coalescer, #396) key on.
 *
 * `occurrence:<Kind>:<definitionId>:<yyyy-mm-dd>` — the *firing identity*: one stable key per firing per
 * day, which is what lets the flush-time coalescer recognise two writes as addressing the same thing.
 * It is **not** an enqueue-time dedupe key — [OutboxStore.enqueue] appends unconditionally, which is
 * precisely why the coalescer exists (see [coalesceOccurrences]).
 *
 * - `<Kind>` is the [ItemKind] **enum name** (`Habit`/`Chore`/`Event`), not the wire token — this is a
 *   local persistence key, never sent, so it follows the same "store the enum name" convention as the
 *   cache codecs.
 * - `<definitionId>` is the recurring **item** id the endpoints key on (the chain Head the feed emits
 *   as `task_id`), *not* the series id. See the [OccurrenceMutation] note: the series id in that slot
 *   404s, and a 404 maps to *success*, so the write vanishes silently.
 *
 * Ids are UUIDs (no `:`) and the date is a fixed-width ISO day, so every segment splits cleanly on `:`.
 * [parse] is **total and tolerant** (ADR-0005): a non-occurrence target, an unknown kind token, a blank
 * id or an unparseable date all yield `null` rather than throwing — a caller scanning a mixed outbox
 * (create / task / plan / comment rows, plus whatever a future build queues) must never crash on a row
 * it does not understand.
 */
object OccurrenceTargets {

    const val PREFIX: String = "occurrence:"

    /** The `occurrence:<Kind>:<definitionId>:<date>` target for one firing. */
    fun of(kind: ItemKind, definitionId: String, date: LocalDate): String =
        "$PREFIX${kind.name}:$definitionId:$date"

    /**
     * The decoded firing identity of an [target], or `null` for anything that is not a well-formed
     * occurrence target (see the tolerance note above).
     */
    fun parse(target: String): OccurrenceTarget? {
        if (!target.startsWith(PREFIX)) return null
        val parts = target.removePrefix(PREFIX).split(':')
        if (parts.size != 3) return null
        val (kindToken, definitionId, dateToken) = parts
        val kind = ItemKind.entries.firstOrNull { it.name == kindToken } ?: return null
        if (definitionId.isBlank()) return null
        val date = runCatching { LocalDate.parse(dateToken) }.getOrNull() ?: return null
        return OccurrenceTarget(kind, definitionId, date)
    }
}

/** The decoded segments of an `occurrence:<Kind>:<definitionId>:<date>` outbox target. */
data class OccurrenceTarget(
    val kind: ItemKind,
    /** The recurring **item** id the occurrence endpoints key on (the chain Head), not the series id. */
    val definitionId: String,
    val date: LocalDate,
)

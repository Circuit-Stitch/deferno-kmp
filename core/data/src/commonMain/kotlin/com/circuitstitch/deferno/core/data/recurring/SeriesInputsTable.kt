package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SeriesInputs

/**
 * The one accessor over `seriesInputsEntity` + its `seriesOverrideEntity` children (#410, ADR-0053
 * decision 2), scoped to a single [kind]. The three recurring local stores each hold one and stitch its
 * results onto the definitions they read, so the two-table read/write lives here once instead of three
 * times — the same reasoning that gave `RecurringEntityCodec` a single home.
 *
 * **Why an accessor rather than a join.** A definition has *many* overrides, so there is no flat row to
 * `LEFT JOIN` onto `habitEntity`; and the inputs are deliberately kind-neutral (`kind` is a column, not
 * a table) so the Item projection can read all four kinds in one pass. Stitching in Kotlin keeps both.
 *
 * **These reads are plain queries, not a second observed `Flow`, and [write] is why that is safe.**
 * Combining two SQLDelight flows looks like the more principled option and is in fact the broken one:
 * `mapToList` re-queries off the notification asynchronously, so two independently-observed tables
 * produce a genuine race — an upsert momentarily emits the NEW series map beside the OLD definition
 * list, and a freshly created row visibly flickers out of the tree. (That is not a hypothesis; it is
 * what the existing `RecurringLocalStoreTest` caught the moment the flows were combined.)
 *
 * [write] instead commits the definition row and its inputs in ONE transaction, so SQLDelight holds
 * every notification until the outermost commit and the definition's own flow cannot re-emit until the
 * inputs are already there. An inline read inside that flow's `map` therefore always sees a consistent
 * pair. The invariant is narrow and enforced here rather than remembered: **series rows are only ever
 * written through [write], which always writes the definition too.**
 */
internal class SeriesInputsTable(
    private val db: DefernoDatabase,
    kind: ItemKind,
) {
    // The stored token is the DOMAIN enum's `.name`, never a wire token — the `occurrenceFactEntity`
    // rule (these columns are a local vocabulary, and the wire's spelling is not ours to inherit).
    private val kindName = kind.name
    private val inputs get() = db.seriesInputsEntityQueries
    private val overrides get() = db.seriesOverrideEntityQueries

    /**
     * Every cached definition's inputs for this kind, keyed by definition id — two queries for the whole
     * list, not two per row. A definition with no row is simply **absent from the map**, which a caller
     * must project as a `null` [SeriesInputs] ("this device cannot reproduce that grid"), never as an
     * empty one.
     */
    fun readAll(): Map<String, SeriesInputs> {
        val overridesByDefinition = overrides.selectByKind(kindName).executeAsList().groupBy { it.definition_id }
        return inputs.selectByKind(kindName).executeAsList().mapNotNull { row ->
            row.toDomain(overridesByDefinition[row.definition_id].orEmpty())?.let { row.definition_id to it }
        }.toMap()
    }

    /** One definition's inputs, or `null` when it has no row (or holds one this build cannot read). */
    fun read(definitionId: String): SeriesInputs? =
        inputs.selectOne(kindName, definitionId).executeAsOneOrNull()
            ?.toDomain(overrides.selectFor(kindName, definitionId).executeAsList())

    /**
     * Commits [definitionRow] and [definitionId]'s inputs together, replacing the inputs wholesale or
     * clearing them when [series] is `null`.
     *
     * **One transaction, and that is load-bearing** — see the class KDoc. It is also the only way the
     * pair is ever *observed* consistently: a reader that caught the definition between the two writes
     * would see a recurring row whose grid had briefly vanished.
     *
     * **Clear-then-seed, and the clear runs even for a `null`.** An override the server has stopped
     * reporting must not survive as a hole in a grid it no longer belongs to, and a definition whose
     * whole block went away must lose its inputs rather than keep a stale grid that outlived the series
     * behind it. A re-seed that only inserted would leave yesterday's exceptions behind, because an
     * override is identified by its slot.
     */
    fun write(definitionId: String, series: SeriesInputs?, definitionRow: () -> Unit) = db.transaction {
        definitionRow()
        overrides.deleteFor(kindName, definitionId)
        if (series == null) {
            inputs.deleteFor(kindName, definitionId)
        } else {
            inputs.insertOrReplace(series.toEntity(kindName, definitionId))
            series.toOverrideEntities(kindName, definitionId).forEach(overrides::insertOrReplace)
        }
    }
}

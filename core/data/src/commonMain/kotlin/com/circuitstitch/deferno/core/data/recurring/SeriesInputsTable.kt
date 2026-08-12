package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.SeriesInputs

/**
 * The one accessor over `seriesInputsEntity` and its `seriesOverrideEntity` children (#410, ADR-0053
 * decision 2). The item local store holds one and stitches its results onto the rows it reads, so the
 * two-table read and write lives here once — the same reasoning that gave `RecurringEntityCodec` a
 * single home.
 *
 * It was scoped to a single kind while the cache held four kind-shaped tables, and three stores each
 * held their own. Both tables are keyed on the item id alone since #422, so one instance serves the one
 * store.
 *
 * **Why an accessor rather than a join.** An item has *many* overrides, so there is no flat row to
 * `LEFT JOIN` onto `itemEntity`. Stitching in Kotlin keeps both tables adapter-free.
 *
 * **These reads are plain queries, not a second observed `Flow`, and [write] is why that is safe.**
 * Combining two SQLDelight flows looks like the more principled option and is in fact the broken one:
 * `mapToList` re-queries off the notification asynchronously, so two independently-observed tables
 * produce a genuine race — an upsert momentarily emits the NEW series map beside the OLD item list, and
 * a freshly created row visibly flickers out of the tree. That is not a hypothesis; it is what the
 * store's own test caught the moment the flows were combined.
 *
 * [write] instead commits the item row and its inputs in ONE transaction, so SQLDelight holds every
 * notification until the outermost commit and the item's own flow cannot re-emit until the inputs are
 * already there. An inline read inside that flow's `map` therefore always sees a consistent pair. The
 * invariant is narrow and enforced here rather than remembered: **series rows are only ever written
 * through [write], which always writes the item row too.**
 */
internal class SeriesInputsTable(private val db: DefernoDatabase) {

    private val inputs get() = db.seriesInputsEntityQueries
    private val overrides get() = db.seriesOverrideEntityQueries

    /**
     * Every cached item's inputs, keyed by item id — two queries for the whole list, not two per row. An
     * item with no row is simply **absent from the map**, which a caller must project as a `null`
     * [SeriesInputs] ("this device cannot reproduce that grid"), never as an empty one.
     */
    fun readAll(): Map<String, SeriesInputs> {
        val overridesByItem = overrides.selectAll().executeAsList().groupBy { it.item_id }
        return inputs.selectAll().executeAsList().mapNotNull { row ->
            row.toDomain(overridesByItem[row.item_id].orEmpty())?.let { row.item_id to it }
        }.toMap()
    }

    /** One item's inputs, or `null` when it has no row (or holds one this build cannot read). */
    fun read(itemId: String): SeriesInputs? =
        inputs.selectOne(itemId).executeAsOneOrNull()?.toDomain(overrides.selectFor(itemId).executeAsList())

    /**
     * Commits [itemRow] and [itemId]'s inputs together, replacing the inputs wholesale or clearing them
     * when [series] is `null`.
     *
     * **One transaction, and that is load-bearing** — see the class KDoc. It is also the only way the
     * pair is ever *observed* consistently: a reader that caught the item between the two writes would
     * see a recurring row whose grid had briefly vanished.
     *
     * **Clear-then-seed, and the clear runs even for a `null`.** An override the server has stopped
     * reporting must not survive as a hole in a grid it no longer belongs to, and an item whose whole
     * block went away must lose its inputs rather than keep a stale grid that outlived the series behind
     * it. A re-seed that only inserted would leave yesterday's exceptions behind, because an override is
     * identified by its slot.
     */
    fun write(itemId: String, series: SeriesInputs?, itemRow: () -> Unit) = db.transaction {
        itemRow()
        overrides.deleteFor(itemId)
        if (series == null) {
            inputs.deleteFor(itemId)
        } else {
            inputs.insertOrReplace(series.toEntity(itemId))
            series.toOverrideEntities(itemId).forEach(overrides::insertOrReplace)
        }
    }
}

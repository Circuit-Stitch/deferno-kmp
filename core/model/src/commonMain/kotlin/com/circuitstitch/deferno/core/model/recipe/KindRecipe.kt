@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Translation between the plugin model and the four kinds the wire still speaks (ADR-0056) — an
 * [Item] definition through the eight typed methods below, and one dated firing of it through
 * [read] and [writeFact].
 *
 * The four-kind wire survives only until the backend's own port lands. Everything that knows about
 * kinds lives in this one package and does nothing else, so the cutover deletes a directory. Wire
 * *encoding* stays in `core:network`; `core:data` is what calls these.
 *
 * There are two recipes per kind:
 *
 * - The **parity recipe** ([ParityRecipe]) reproduces today's behaviour exactly, including the parts
 *   that are wrong, and is what the migration is gated on. Round-trip identity over it is a `check`
 *   gate, so behaviour parity is asserted rather than reviewed.
 * - The **target recipe** is the shape the model actually wants. It lands later, one Family at a
 *   time. It implements this same interface, which is why the interface exists rather than the parity
 *   functions being top-level.
 *
 * The distinction is load-bearing: the reference fixtures in `AspectualShapes.kt` give a recurring
 * chore a skipped-if-missed policy and an appointment a creates-follow-up policy. Neither is what this
 * client does, so building the translation from them would ship a behaviour change as a refactor.
 *
 * The write direction is four typed methods because an [Item] names no kind. The caller knows which
 * row it is rebuilding, four methods say so at the type level, and they make the round trip
 * `writeTask(read(t)) == t` state itself. A caller holding a kind token instead uses [write].
 */
@ObjCName("PluginKindRecipe")
interface KindRecipe {

    fun read(task: Task): Item

    fun read(habit: Habit): Item

    fun read(chore: Chore): Item

    fun read(event: Event): Item

    fun writeTask(item: Item): Task

    fun writeHabit(item: Item): Habit

    fun writeChore(item: Item): Chore

    fun writeEvent(item: Item): Event

    /**
     * One dated firing's stored fact, read into the plugin-shaped record that owns it.
     *
     * `OccurrenceFact` is keyed `(kind, definitionId, date)` and an [Occurrence] `(itemId, date)`, so
     * the kind is the one part of the key that does not cross — it is the wire's discriminator, and
     * this package is where knowledge of it is allowed to live. The write direction takes it back.
     */
    fun read(fact: OccurrenceFact): Occurrence

    /**
     * [occurrence] written back as a firing of [kind], or **`null` when nothing is on record**.
     *
     * Unlike the four `writeX` methods there is no row to fall back on: a fact *is* its plugin, so an
     * [Occurrence] carrying only device-local plugins — or none — is not an unresolved row, it is the
     * absence of one. Absence is the honest record for a date with nothing on it, and the
     * Scheduled-versus-Missed reading over it is derived at render time.
     */
    fun writeFact(occurrence: Occurrence, kind: ItemKind): OccurrenceFact?

    /** Read whichever of the four rows [row] holds. The typed overloads above are the primitive. */
    fun read(row: KindRow): Item = when (row) {
        is KindRow.OfTask -> read(row.task)
        is KindRow.OfHabit -> read(row.habit)
        is KindRow.OfChore -> read(row.chore)
        is KindRow.OfEvent -> read(row.event)
    }

    /**
     * Write [item] as a row of [kind] — for a caller holding a kind token rather than a static type.
     *
     * The four `writeX` methods stay the primitive; this only picks between them.
     */
    fun write(item: Item, kind: ItemKind): KindRow = when (kind) {
        ItemKind.Task -> KindRow.OfTask(writeTask(item))
        ItemKind.Habit -> KindRow.OfHabit(writeHabit(item))
        ItemKind.Chore -> KindRow.OfChore(writeChore(item))
        ItemKind.Event -> KindRow.OfEvent(writeEvent(item))
    }
}

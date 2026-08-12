@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.plugin.Item
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Translation between a plugin [Item] and one of the four kinds the wire still speaks (ADR-0056).
 *
 * ### This package is written to be deleted
 *
 * The four-kind wire survives only until the backend's own port lands. Everything that knows about
 * kinds lives in this one package with no other responsibility, so the cutover removes a directory
 * rather than unpicking a layer spread across 62 mapper files. Nothing here may acquire a second job:
 * wire *encoding* stays in `core:network`, and `core:data` is what calls these.
 *
 * ### Two recipes per kind, not one
 *
 * - The **parity recipe** ([ParityRecipe]) reproduces today's behaviour exactly, including the parts
 *   that are wrong, and is what the migration is gated on. Round-trip identity over it is a `check`
 *   gate; behaviour parity is asserted rather than reviewed.
 * - The **target recipe** is the shape the model actually wants. It lands later, one Family at a
 *   time, once #420 ratifies what each Family should say. It implements this same interface, which is
 *   why the interface exists at all rather than the parity functions being top-level.
 *
 * The distinction is load-bearing. The reference fixtures in `AspectualShapes.kt` give a recurring
 * chore a skipped-if-missed policy and an appointment a creates-follow-up policy; neither is what
 * this client does, and building the translation from them would have shipped a behaviour change
 * disguised as a refactor.
 *
 * ### Why the write direction is four methods
 *
 * An [Item] names no kind — that is the point of it — so nothing in a plugin list says which of the
 * four rows to rebuild. The caller knows, because it is holding the row it read. Four typed methods
 * say so at the type level instead of taking a kind token and returning something the caller has to
 * cast, and they make the round trip `writeTask(read(t)) == t` state itself.
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
}

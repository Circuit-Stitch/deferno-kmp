@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * One of the four rows the wire still speaks, typed — a [Task], [Habit], [Chore] or [Event] under one
 * name.
 *
 * It exists so the "which of the four kinds is this" dispatch is written once, in [KindRecipe.read]
 * and [KindRecipe.write], instead of at every call site that holds a row. It is also what
 * [Admission.Admitted.row] hands back, so an admitted set comes with its wire row rather than an `Any`
 * the caller has to rebuild.
 */
@ObjCName("PluginKindRow")
sealed interface KindRow {

    /** Which of the four kinds this row is. */
    val kind: ItemKind

    @ObjCName("PluginKindRowOfTask")
    data class OfTask(val task: Task) : KindRow {
        override val kind get() = ItemKind.Task
    }

    @ObjCName("PluginKindRowOfHabit")
    data class OfHabit(val habit: Habit) : KindRow {
        override val kind get() = ItemKind.Habit
    }

    @ObjCName("PluginKindRowOfChore")
    data class OfChore(val chore: Chore) : KindRow {
        override val kind get() = ItemKind.Chore
    }

    @ObjCName("PluginKindRowOfEvent")
    data class OfEvent(val event: Event) : KindRow {
        override val kind get() = ItemKind.Event
    }
}

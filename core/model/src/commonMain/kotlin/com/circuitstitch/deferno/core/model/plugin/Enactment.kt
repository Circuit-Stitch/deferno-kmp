@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * Where an item has got to in its own lifecycle, and when it stopped — the **definition-scoped** half
 * of Enactment. The occurrence-scoped half (`OccurrenceFact`'s resolution, `doneAt` and the deadline
 * the firing carried) is [Scope.Occurrence], and a plugin instance answers exactly one [Scope].
 *
 * [Lifecycle] is sealed because the four kinds carry two unrelated lifecycles: a Task has a
 * [WorkingState] (`Open`/`InProgress`/`InReview`/`Done`/`Dropped`), a Habit, Chore or Event a
 * [DefinitionState] (`Active`/`InReview`/`Archived`). Every row has exactly one, and a parity recipe
 * carries whichever it had — `Dropped` and `Archived` are different claims and code branches on both.
 */
@ObjCName("PluginProgress")
data class Progress(
    val lifecycle: Lifecycle = Lifecycle.Unstated,
    /**
     * When the doing stopped. Task-only on the wire, and paired against every [WorkingState] in the
     * corpus: the wire can carry a finish timestamp on a row that is not `Done`, so the round trip
     * survives it. Doneness is read from [lifecycle], never inferred from this field.
     */
    val finishedAt: Instant? = null,
) : Enactment {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Progress()
}

/** Which lifecycle a row has, and where it has got to. */
@ObjCName("PluginLifecycle")
sealed interface Lifecycle {

    /** No lifecycle stated. The degenerate value; no row the four-kind wire carries reads as this. */
    data object Unstated : Lifecycle

    /** A Task's progress through its own lifecycle. */
    data class Working(val state: WorkingState) : Lifecycle

    /** A recurring definition's "light switch". */
    data class Definition(val state: DefinitionState) : Lifecycle

    /**
     * Whether this row has reached an end of its lifecycle — a Done/Dropped Task or an Archived
     * definition. The one point the two lifecycles agree, derived here rather than at each call site.
     */
    val isTerminal: Boolean
        get() = when (this) {
            Unstated -> false
            is Working -> state.isTerminal
            is Definition -> state == DefinitionState.Archived
        }
}

/**
 * The felt quality of the doing — how productive it was. Task-only on the wire, and carried on the
 * **definition** row rather than per firing. Separate from [Progress] because the two compose freely:
 * the wire has always allowed a row to record that it stopped without recording how it felt.
 */
@ObjCName("PluginTrackable")
data class Trackable(val productive: Double? = null) : Enactment {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Trackable()
}

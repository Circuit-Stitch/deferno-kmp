package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant

/**
 * Where an item has got to in its own lifecycle, and when it stopped — the **definition-scoped** half
 * of Enactment.
 *
 * ### Why the lifecycle is a sealed type rather than two nullable enums
 *
 * Today's four kinds carry two unrelated lifecycles that no type relates: a Task has a
 * [WorkingState] (`Open`/`InProgress`/`InReview`/`Done`/`Dropped`) and a Habit, Chore or Event has a
 * [DefinitionState] (`Active`/`InReview`/`Archived`), the "light switch". Every row has exactly one,
 * and which one it has is a fact about its kind — which is precisely the shape the re-cut is
 * deleting.
 *
 * A parity recipe may not merge them: `Dropped` and `Archived` are different claims today and code
 * branches on both. So [Lifecycle] carries whichever one the row had, faithfully, and the *merge*
 * becomes a target-recipe question (#420) with a name to be asked about. Two nullable enum fields
 * would have said the same thing while admitting the two states nothing can be in — both set, and
 * neither.
 *
 * ### What is deliberately not here yet
 *
 * The #418 table also lists "the Occurrence resolution" under this family. That half is
 * [Scope.Occurrence] — it maps `OccurrenceFact`'s resolution, `doneAt` and the deadline the firing
 * carried when it resolved — and a plugin instance answers exactly one [Scope], so it cannot share
 * this type. It lands with the Occurrence corpus; the round-trip gate today sweeps definition rows,
 * which is what the four kinds are.
 */
data class Progress(
    val lifecycle: Lifecycle = Lifecycle.Unstated,
    /**
     * When the doing stopped. Task-only on the wire.
     *
     * **Producted against every [WorkingState] in the corpus on purpose.** The wire can carry a
     * finish timestamp on a row that is not `Done`, so the round trip has to survive that rather
     * than tidy it away — and a reader that infers doneness from this field alone is the defect
     * ADR-0055 cites (a licence handed out for a driving test that was failed).
     */
    val finishedAt: Instant? = null,
) : Enactment {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Progress()
}

/** Which lifecycle a row has, and where it has got to. See [Progress] for why this is sealed. */
sealed interface Lifecycle {

    /** No lifecycle stated. The degenerate value; no row the four-kind wire carries reads as this. */
    data object Unstated : Lifecycle

    /** A Task's progress through its own lifecycle. */
    data class Working(val state: WorkingState) : Lifecycle

    /** A recurring definition's "light switch". */
    data class Definition(val state: DefinitionState) : Lifecycle

    /**
     * Whether this row has reached an end of its lifecycle — the de-emphasis signal the Item tree
     * already renders (`Item.isTerminal`): a Done/Dropped Task or an Archived definition.
     *
     * The one place the two lifecycles genuinely agree, derived here rather than restated at each
     * call site the way it is today.
     */
    val isTerminal: Boolean
        get() = when (this) {
            Unstated -> false
            is Working -> state.isTerminal
            is Definition -> state == DefinitionState.Archived
        }
}

/**
 * The felt quality of the doing — how productive it was.
 *
 * Task-only on the wire, and carried on the **definition** row rather than per firing, which is
 * faithful to today and is *not* what the reference model does (it puts affect on the Occurrence,
 * arguing that a definition-level mood is a prediction rather than a record). That disagreement is
 * real and is left standing: moving it is a target-recipe change, not a translation.
 *
 * Separate from [Progress] because the two compose freely — a row can record that it stopped without
 * recording how it felt, and the wire has always allowed either without the other.
 */
data class Trackable(val productive: Double? = null) : Enactment {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Trackable()
}

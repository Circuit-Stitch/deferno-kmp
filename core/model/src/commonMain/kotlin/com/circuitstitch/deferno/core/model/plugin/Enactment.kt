@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.completionResolution
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * Where an item has got to in its own lifecycle, and when it stopped — the **definition-scoped** half
 * of Enactment. The occurrence-scoped half is [Outcome], and a plugin instance answers exactly one
 * [Scope], which is why the two are separate members rather than one record with a nullable date.
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

/**
 * What is **on record** for one dated firing — the [Scope.Occurrence] half of Enactment, and the
 * plugin-shaped home of an `OccurrenceFact`. The whole of that fact beyond its key lives here, which
 * is why an [Occurrence] carrying no [Outcome] is not a row at all rather than an empty one.
 *
 * ### Silence is not `Scheduled`
 *
 * [resolution] is nullable, and the null means *nothing is on record for this date*. That is a
 * different claim from a stored [OccurrenceResolution.Scheduled], which means *the server holds a row
 * that records no progress* — an Event stores exactly that, and a Chore's equivalent date is the one
 * it holds nothing for. Collapsing the two would make the recipe unable to tell a written row from an
 * absent one, so the degenerate value is all-null and a stored `Scheduled` [saysSomething].
 *
 * A [doneAt] or a [carriedDeadline] with no resolution is therefore a record of something that was
 * never recorded, which [validate] reports.
 */
@ObjCName("PluginOutcome")
data class Outcome(
    val resolution: OccurrenceResolution? = null,
    /** When the doing was ticked. Null on every arm but the two `Done` ones, on every kind's wire. */
    val doneAt: Instant? = null,
    /**
     * The deadline this firing carried **at the time it was resolved** — frozen, not the definition's
     * live one, which is a moving `Recurrence` cursor that has since walked on. It sits in Enactment
     * rather than beside [Anchor] for two reasons: [Anchor] is [Scope.Definition], so no member of it
     * could sit here at all, and the punctuality split is a function of this value together with
     * [doneAt], so separating them would put half of one reading on each of two records.
     *
     * Null where nothing was ever synced: a firing resolved offline has no deadline on record, and
     * with nothing to be late against `completionResolution` reads that as on time.
     */
    val carriedDeadline: Instant? = null,
) : Enactment {

    override val scope get() = Scope.Occurrence
    override val reach get() = Reach.Wire
    override val degenerate get() = Outcome()

    /** Whether the server holds anything at all for this date — see the class KDoc. */
    val isOnRecord: Boolean get() = resolution != null

    /**
     * Whether the stored punctuality differs from what [completionResolution] would decide from the
     * two fields beside it. A **reading, never a correction** — the same posture [Anchor.Appointment]
     * takes toward its `allDayFlag`, and for the same reason: the disagreement is representable, rows
     * carrying it exist, and a recipe that recomputed the label would rewrite them.
     *
     * It is not always a defect. An Event's Done arm hard-codes on-time whatever the clock says
     * (*"events never produce DoneLate"*), so an appointment ticked after it began disagrees **by the
     * server's own rule** — which is what [Anchor.latenessIsMeaningful] says in the definition's
     * vocabulary. Whether the pair is legal at all is `latenessProblems`, across both records.
     */
    val punctualityDisagrees: Boolean
        get() {
            val done = doneAt ?: return false
            if (resolution != OccurrenceResolution.DoneOnTime && resolution != OccurrenceResolution.DoneLate) {
                return false
            }
            return resolution != completionResolution(done, carriedDeadline)
        }

    override fun validate(): List<String> = buildList {
        if (resolution == null && (doneAt != null || carriedDeadline != null)) {
            add("nothing is on record for this date, so it cannot carry a doneAt or a deadline")
        }
    }
}

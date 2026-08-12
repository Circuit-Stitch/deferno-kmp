package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.SeriesInputs

/**
 * The thing happens many times on a rule — the wire-backed half of [Unfolding].
 *
 * ### It wraps; it does not restate
 *
 * ADR-0053 pins offline occurrence expansion against a corpus generated from the Rust, and #418's
 * first non-negotiable is that the expander is not touched. So this plugin holds the existing
 * [Recurrence] and [SeriesInputs] **by reference** and adds no cadence vocabulary of its own. The
 * reference model in `DefernoPlugins` duplicates that vocabulary only because its three modules are
 * forbidden from importing one another; that is not a constraint here, and copying the duplication
 * across would have created a second definition of "every other Tuesday" for the corpus to disagree
 * with.
 *
 * ### Absence is determinate here
 *
 * No [Repeats] loaded means *this does not repeat* — unlike the bound under the same meaning family
 * (#419), where absence means *nobody has said*. Two kinds of absence, and the model does not
 * pretend they are one: the degenerate value below is a real answer, not an underspecified one.
 *
 * ### The fields, and why each is nullable
 *
 * - [recurrence] — `null` on a Task, and on a recurring definition whose rule did not survive the
 *   wire. The second case is real and the corpus carries it.
 * - [seriesId] — names the series a firing belongs to; **never** a path key (#380), because every
 *   kind-scoped route keys on the definition id.
 * - [series] — the ADR-0053 expansion inputs. `null` is the wire's deliberate **elision** ("this
 *   device cannot reproduce that grid"), never an empty grid, and the two must not be conflated.
 * - [cadenceMode] — how the schedule advances once a firing is closed out, **not** which days it
 *   fires on. Chore-only on the wire, so `null` here means *this kind has no such field* rather than
 *   *unknown*: a Chore's own absent token already means `Rolling`, which the Chore recipe supplies.
 */
data class Repeats(
    val recurrence: Recurrence? = null,
    val seriesId: String? = null,
    val series: SeriesInputs? = null,
    val cadenceMode: CadenceMode? = null,
) : Unfolding {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Repeats()

    /** Whether a rule survived the wire, and therefore whether a grid can be asked for at all. */
    val hasRule: Boolean get() = recurrence != null

    /** Whether this device can reproduce the grid offline — a rule *and* its inputs (ADR-0053). */
    val isExpandable: Boolean get() = recurrence != null && series != null
}

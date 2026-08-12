@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.SeriesInputs
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The thing happens many times on a rule — the wire-backed half of [Unfolding]. It holds the existing
 * [Recurrence] and [SeriesInputs] **by reference** and adds no cadence vocabulary of its own, so the
 * ADR-0053 corpus pins one definition of "every other Tuesday", not two. No [Repeats] loaded means
 * *this does not repeat* — determinate, unlike the bound under the same meaning family, where absence
 * means *nobody has said*.
 *
 * - [recurrence] — `null` on a Task, and on a recurring definition whose rule did not survive the
 *   wire; the corpus carries that second case.
 * - [seriesId] — names the series a firing belongs to, **never** a path key: every kind-scoped route
 *   keys on the definition id.
 * - [series] — the expansion inputs. `null` is the wire's deliberate **elision** ("this device cannot
 *   reproduce that grid"), never an empty grid.
 * - [cadenceMode] — how the schedule advances once a firing is closed out, **not** which days it
 *   fires on. Chore-only, so `null` means *this kind has no such field*; a Chore's absent token means
 *   `Rolling`, which the Chore recipe supplies.
 */
@ObjCName("PluginRepeats")
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

    /** Whether this device can reproduce the grid offline — a rule *and* its inputs. */
    val isExpandable: Boolean get() = recurrence != null && series != null
}

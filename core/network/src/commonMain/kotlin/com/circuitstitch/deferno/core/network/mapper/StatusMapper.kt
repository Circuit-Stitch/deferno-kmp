package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.DerivedChoreOccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire

/**
 * The wire-status → domain-state condensation (ADR-0011 "condense at the edge", CONTRACT-NOTES →
 * "Status"). The wire's six overloaded "status" enums collapse here into the three distinctly-named
 * domain enums in `core:model`. Every `...Wire.Unknown` (the coerced additive-token fallback)
 * degrades to a **safe default** so a row the backend statuses additively stays usable rather than
 * disappearing or crashing the reader.
 */

/**
 * `TaskStatus` → [WorkingState]. [TaskStatusWire.Unknown] degrades to [WorkingState.Open] so an
 * additively-statused Task stays visible as active work rather than being hidden as terminal.
 */
fun TaskStatusWire.toWorkingState(): WorkingState = when (this) {
    TaskStatusWire.Open -> WorkingState.Open
    TaskStatusWire.InProgress -> WorkingState.InProgress
    TaskStatusWire.InReview -> WorkingState.InReview
    TaskStatusWire.Done -> WorkingState.Done
    TaskStatusWire.Dropped -> WorkingState.Dropped
    TaskStatusWire.Unknown -> WorkingState.Open
}

/**
 * `DefStatus` → [DefinitionState]. [DefStatusWire.Unknown] degrades to [DefinitionState.Active] so a
 * recurring definition with an additive status keeps firing rather than silently switching off.
 */
fun DefStatusWire.toDefinitionState(): DefinitionState = when (this) {
    DefStatusWire.Active -> DefinitionState.Active
    DefStatusWire.InReview -> DefinitionState.InReview
    DefStatusWire.Archived -> DefinitionState.Archived
    DefStatusWire.Unknown -> DefinitionState.Active
}

/**
 * `OccurrenceStatus` → [OccurrenceState]. `dropped` (the event terminal) condenses to
 * [OccurrenceState.Skipped]; [OccurrenceStatusWire.Unknown] degrades to [OccurrenceState.Scheduled]
 * (an unresolved firing) rather than fabricating a resolution.
 */
fun OccurrenceStatusWire.toOccurrenceState(): OccurrenceState = when (this) {
    OccurrenceStatusWire.Scheduled -> OccurrenceState.Scheduled
    OccurrenceStatusWire.InProgress -> OccurrenceState.InProgress
    OccurrenceStatusWire.DoneOnTime -> OccurrenceState.DoneOnTime
    OccurrenceStatusWire.DoneLate -> OccurrenceState.DoneLate
    OccurrenceStatusWire.Dropped -> OccurrenceState.Skipped
    OccurrenceStatusWire.Unknown -> OccurrenceState.Scheduled
}

/**
 * `DerivedChoreOccurrenceStatus` → [OccurrenceState] — the read superset, including the
 * server-derived `scheduled`/`missed`. `skipped` condenses to [OccurrenceState.Skipped], `missed` is
 * kept distinct as [OccurrenceState.Missed]; [DerivedChoreOccurrenceStatusWire.Unknown] degrades to
 * [OccurrenceState.Scheduled].
 */
fun DerivedChoreOccurrenceStatusWire.toOccurrenceState(): OccurrenceState = when (this) {
    DerivedChoreOccurrenceStatusWire.Scheduled -> OccurrenceState.Scheduled
    DerivedChoreOccurrenceStatusWire.Missed -> OccurrenceState.Missed
    DerivedChoreOccurrenceStatusWire.InProgress -> OccurrenceState.InProgress
    DerivedChoreOccurrenceStatusWire.DoneOnTime -> OccurrenceState.DoneOnTime
    DerivedChoreOccurrenceStatusWire.DoneLate -> OccurrenceState.DoneLate
    DerivedChoreOccurrenceStatusWire.Skipped -> OccurrenceState.Skipped
    DerivedChoreOccurrenceStatusWire.Unknown -> OccurrenceState.Scheduled
}

/**
 * Which recurring kind an [OccurrenceAction] is being written against (ADR-0011 read/write
 * asymmetry). Selects the kind-appropriate wire token for [OccurrenceAction.Skip]: a chore is
 * `skipped`, an event is `dropped`.
 */
enum class OccurrenceKind { Chore, Event }

/**
 * The write-mapper for the coarse domain [OccurrenceAction] → its wire token (ADR-0011,
 * CONTRACT-NOTES → read/write asymmetry). The client only ever *sets* `in_progress`/`done`/
 * `skipped`|`dropped`; the finer read states (`scheduled`, `missed`, the on-time/late split) are
 * server-derived and never written. [OccurrenceAction.Skip] diverges by [kind]: chore → `skipped`,
 * event → `dropped`.
 */
fun OccurrenceAction.toWireToken(kind: OccurrenceKind): String = when (this) {
    OccurrenceAction.Start -> "in_progress"
    OccurrenceAction.Complete -> "done"
    OccurrenceAction.Skip -> when (kind) {
        OccurrenceKind.Chore -> "skipped"
        OccurrenceKind.Event -> "dropped"
    }
}

@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.LocalTime

/**
 * When the thing is committed to happen (ADR-0055).
 *
 * All four kinds carry one field called `completeBy`. On a Task, Habit or Chore it is a **deadline**,
 * due *by* that instant and satisfiable late; on an Event it is a **start**, happening *at* that
 * instant and never satisfiable late (`validate_for_event` rejects `DoneLate` outright). [Deadline]
 * and [Appointment] name the two claims separately, and the parity recipe carries each instant across
 * unchanged. [family] names [Anchor], so the two cannot both load; [Targeted] is a separate exclusive
 * family, because a soft target and a hard deadline compose.
 */
@ObjCName("PluginAnchor")
sealed class Anchor : Temporal {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val family: KClass<out Plugin> get() = Anchor::class

    /** [Unanchored]: silence in this family is a real member, not an absence. Answered once, here. */
    override val degenerate: Plugin get() = Unanchored

    /**
     * A member here can be silent without being [Unanchored]: both dated members are fully nullable,
     * so an all-null [Deadline] is a different *value* making the same *claim*, and the inherited
     * `this != degenerate` would load it into a sparse list that then carries a plugin saying nothing.
     * The `when` is exhaustive, so a fourth member is a compile error, not a fourth way to be empty.
     */
    override val saysSomething: Boolean
        get() = when (this) {
            Unanchored -> false
            is Deadline -> this != Deadline()
            is Appointment -> this != Appointment()
        }

    /** Wanted, not scheduled. What an item with no Anchor loaded reads as. */
    data object Unanchored : Anchor()

    /**
     * Due *by* an instant, satisfiable late — `completionResolution` compares `doneAt` against this
     * inclusively, so finishing exactly on it is on time. Both fields are nullable because the wire
     * can carry either without the other: a row with a `deadline_time_of_day` and no `complete_by` is
     * representable. The parity recipe builds one unconditionally; it loads whenever either field is
     * present, and when neither is it says nothing and the read is [Unanchored].
     */
    data class Deadline(
        val completeBy: Instant? = null,
        val timeOfDay: LocalTime? = null,
    ) : Anchor()

    /**
     * Happens *at* a time, optionally until another. Cannot be satisfied late. [allDayFlag] is
     * stored, not derived: the server derives `all_day` from the two time-of-day fields being null
     * and ignores it on input, yet still ships the column, so a row whose flag disagrees with its
     * times is representable and a recipe that recomputed it would rewrite such rows. [isAllDay] is
     * the derived reading beside it, and the two are allowed to differ.
     */
    data class Appointment(
        val start: Instant? = null,
        val end: Instant? = null,
        val startTimeOfDay: LocalTime? = null,
        val endTimeOfDay: LocalTime? = null,
        val allDayFlag: Boolean = false,
    ) : Anchor() {
        /** All-day as the server *derives* it — true iff neither clock time is set. */
        val isAllDay: Boolean get() = startTimeOfDay == null && endTimeOfDay == null
    }

    /**
     * Whether finishing after the committed time is a distinguishable outcome. Hand-coded per kind on
     * both sides of the wire today — the client's occurrence mutation branches `ItemKind.Event ->
     * DoneOnTime`, and the server's `validate_for_event` rejects the late variant.
     */
    val latenessIsMeaningful: Boolean get() = this is Deadline
}

/**
 * The **soft** "want done by" date: ranks and surfaces, never drives carry-forward, never moves the
 * calendar, never becomes an occurrence deadline. A separate exclusive family from [Anchor] rather
 * than a field on it, because the server enforces no `targetDate <= completeBy` invariant — a target
 * can sit either side of a deadline, or exist with none. Date-granular; there is no target time-of-day.
 */
@ObjCName("PluginTargeted")
data class Targeted(val targetDate: Instant? = null) : Temporal {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Targeted()
}

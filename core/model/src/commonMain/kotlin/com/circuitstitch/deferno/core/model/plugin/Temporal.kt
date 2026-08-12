@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.LocalTime

/**
 * When the thing is committed to happen — **the valuable family**, and the one that makes the
 * conflation ADR-0055 measured visible for the first time.
 *
 * ### The conflation, reproduced rather than corrected
 *
 * Today all four kinds carry a field called `completeBy`. On a Task, Habit or Chore it is a
 * **deadline** — due *by* that instant, satisfiable late. On an Event it is a **start** — happening
 * *at* that instant, not satisfiable late at all, which the backend hard-codes per kind
 * (`validate_for_event` rejects `DoneLate` outright, "events do not have a late concept"). One field
 * name, two incompatible claims, with no conversion between them.
 *
 * [Deadline] and [Appointment] give the two claims separate names. **The parity recipe changes
 * nothing** — a Task's instant still lands on a `Deadline` and an Event's on an `Appointment`, with
 * no conversion, exactly as today. What the split buys now is that the conflation is *nameable*;
 * correcting it is a target-recipe decision with its own issue (#420), because it changes what
 * existing rows mean.
 *
 * ### One exclusive family, three members
 *
 * [family] names [Anchor], so loading a [Deadline] beside an [Appointment] is caught as the two
 * answers to one question that it is. [Targeted] is a *different* exclusive family under the same
 * Temporal meaning — a soft target and a hard deadline compose, and always have.
 */
@ObjCName("PluginAnchor")
sealed class Anchor : Temporal {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val family: KClass<out Plugin> get() = Anchor::class

    /** Silence in this family is a real member: wanted, not scheduled. Answered once, here. */
    override val degenerate: Plugin get() = Unanchored

    /**
     * Overridden because in *this* family a member can be silent without being [Unanchored].
     *
     * The inherited rule — `this != degenerate` — is right wherever a family's members each carry
     * their own information. Here both dated members are fully nullable (see [Deadline]), so an
     * all-null `Deadline` is a different *value* from `Unanchored` while making the same *claim*.
     * Left to the inherited rule it would load, and a sparse list would carry a plugin that says
     * nothing — which is what makes two plugin lists mean one row.
     *
     * The `when` is exhaustive with no `else`, so a fourth member is a compile error here rather
     * than a silent fourth way to be empty. That is the same trade the abstract [degenerate] makes:
     * a table, but one the compiler maintains.
     */
    override val saysSomething: Boolean
        get() = when (this) {
            Unanchored -> false
            is Deadline -> this != Deadline()
            is Appointment -> this != Appointment()
        }

    /** Wanted, not scheduled. The degenerate value — what an item with no Anchor loaded reads as. */
    data object Unanchored : Anchor()

    /**
     * Due *by* an instant. Can be satisfied late — `completionResolution` compares `doneAt` against
     * this and the bound is inclusive, so finishing exactly on it is on time.
     *
     * **Both fields are nullable, and that is parity rather than sloppiness.** The day comes from
     * [completeBy] and the clock time from [timeOfDay], and the wire can carry either without the
     * other: a summary row with a `deadline_time_of_day` and no `complete_by` is representable, so
     * dropping it here would be a field silently lost on the way back. A [Deadline] loads whenever
     * *either* is present; when neither is, no Anchor loads at all and the read is [Unanchored].
     */
    data class Deadline(
        val completeBy: Instant? = null,
        val timeOfDay: LocalTime? = null,
    ) : Anchor()

    /**
     * Happens *at* a time, optionally until another. Cannot be satisfied late.
     *
     * [allDayFlag] is carried rather than derived, and that is deliberate parity. The server derives
     * `all_day` from the two time-of-day fields being null and ignores it on input, but it still
     * **ships** the column — so a row where the flag disagrees with the times is representable, and
     * a recipe that recomputed it would quietly rewrite such rows. [isAllDay] is the derived reading
     * beside it; the two are allowed to differ, and which one a surface should trust is a #420
     * question rather than something to settle by dropping a field.
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
     * Whether finishing after the committed time is a distinguishable outcome.
     *
     * Read off the shape, where today it is hand-coded per kind on both sides of the wire — the
     * client's occurrence mutation branches `ItemKind.Event -> DoneOnTime` and the server's
     * `validate_for_event` rejects the late variant. Same answer, derived once instead of written
     * twice.
     */
    val latenessIsMeaningful: Boolean get() = this is Deadline
}

/**
 * The **soft** "want done by" date (#375). Ranks and surfaces; never drives carry-forward, never
 * moves the calendar, never becomes an occurrence deadline.
 *
 * A separate exclusive family from [Anchor] rather than a field on it, because the two are
 * independent by design: the server enforces no `targetDate <= completeBy` invariant, so a target
 * can sit either side of a deadline or exist with no deadline at all.
 *
 * Date-granular by intent — there is no target time-of-day, and the wire has no field for one.
 */
@ObjCName("PluginTargeted")
data class Targeted(val targetDate: Instant? = null) : Temporal {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Targeted()
}

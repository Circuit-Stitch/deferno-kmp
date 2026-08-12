@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Attachable
import com.circuitstitch.deferno.core.model.plugin.Blocker
import com.circuitstitch.deferno.core.model.plugin.Core
import com.circuitstitch.deferno.core.model.plugin.Describable
import com.circuitstitch.deferno.core.model.plugin.Importable
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.Plugin
import com.circuitstitch.deferno.core.model.plugin.Prioritizable
import com.circuitstitch.deferno.core.model.plugin.Progress
import com.circuitstitch.deferno.core.model.plugin.Repeats
import com.circuitstitch.deferno.core.model.plugin.Succeeds
import com.circuitstitch.deferno.core.model.plugin.Taggable
import com.circuitstitch.deferno.core.model.plugin.Targeted
import com.circuitstitch.deferno.core.model.plugin.Trackable
import com.circuitstitch.deferno.core.model.plugin.Volition
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The recipe that reproduces **today's behaviour exactly** — the one the migration is gated on.
 *
 * Every field the four-kind wire can carry has a home in some Family, and reading a row into plugins
 * and writing it back produces the same row. `KindRecipeRoundTripTest` asserts that over the whole
 * kind × field-combination corpus, which makes the re-cut checkable rather than reviewable.
 *
 * Three rules govern everything here:
 *
 * 1. **Nothing is corrected on the way through.** The time-of-day that means "due at 5" on three kinds
 *    and "starts at 5" on an Event crosses unchanged. So does a `finishedAt` on a row that is not
 *    `Done`, and an `allDay` flag disagreeing with the clock times beside it. Correcting any of them
 *    belongs to the target recipe; doing it here would make the gate meaningless, since a green round
 *    trip would prove only that the two directions agreed on the same rewrite.
 *
 * 2. **The list stays sparse — a plugin loads only when it says something.** A Family whose fields all
 *    sit at their degenerate values loads nothing, because the total read already returns that value.
 *    That keeps [read] deterministic, with exactly one plugin list per row, and makes the round trip
 *    identity rather than merely equivalence.
 *
 * 3. **A field a kind does not have is not invented.** `desire` is Task-only on the wire, so a Habit
 *    never loads [Volition] and [writeHabit] never looks for one. The plugin model is wider than any
 *    single kind; keeping the client from writing what the wire cannot carry is [Clamp]'s job.
 *
 * Identity, org, title, tree position and sync bookkeeping cross in [Core] rather than in a Family —
 * ten fields all four kinds declare identically, plus the two subtree counts, which roll up over the
 * tree Core owns. They are copied across rather than mapped, which is the saving the re-cut exists
 * for: the other 65% of field declarations that repeat.
 */
@ObjCName("PluginParityRecipe")
object ParityRecipe : KindRecipe {

    // ── Read: a kind row becomes a Core plus a sparse plugin list ──────────────────────────────

    override fun read(task: Task): Item = Item(
        core = Core(
            id = task.id.value,
            orgSlug = task.orgSlug,
            title = task.title,
            parentId = task.parentId?.value,
            childIds = task.children.map { it.value },
            descendantDone = task.descendantDone,
            descendantTotal = task.descendantTotal,
            sequence = task.sequence,
            ref = task.ref,
            dateCreated = task.dateCreated,
            deletedAt = task.deletedAt,
            hydration = task.hydration,
            ownerOrgId = task.ownerOrgId,
        ),
        plugins = sparse(
            Describable(task.description),
            Taggable(task.labels),
            Attachable(task.attachmentCount, task.attachmentTotalSize),
            Prioritizable(task.priority, task.pinned),
            Anchor.Deadline(task.completeBy, task.deadlineTimeOfDay),
            Targeted(task.targetDate),
            Progress(Lifecycle.Working(task.workingState), task.finishedAt),
            Trackable(task.productive),
            Volition(task.desire),
            Blocker(task.blocked, task.isBlocker, task.blockedBy),
            Succeeds(task.nextTaskId?.value),
            Importable(task.external),
        ),
    )

    override fun read(habit: Habit): Item = Item(
        core = Core(
            id = habit.id.value,
            orgSlug = habit.orgSlug,
            title = habit.title,
            parentId = habit.parentId?.value,
            sequence = habit.sequence,
            ref = habit.ref,
            dateCreated = habit.dateCreated,
            deletedAt = habit.deletedAt,
            hydration = habit.hydration,
            ownerOrgId = habit.ownerOrgId,
        ),
        plugins = sparse(
            Describable(habit.description),
            Taggable(habit.labels),
            Prioritizable(habit.priority, habit.pinned),
            Anchor.Deadline(habit.completeBy, habit.deadlineTimeOfDay),
            Targeted(habit.targetDate),
            Repeats(habit.recurrence, habit.seriesId, habit.series),
            Progress(Lifecycle.Definition(habit.definitionState)),
            Blocker(habit.blocked, habit.isBlocker),
        ),
    )

    override fun read(chore: Chore): Item = Item(
        core = Core(
            id = chore.id.value,
            orgSlug = chore.orgSlug,
            title = chore.title,
            parentId = chore.parentId?.value,
            sequence = chore.sequence,
            ref = chore.ref,
            dateCreated = chore.dateCreated,
            deletedAt = chore.deletedAt,
            hydration = chore.hydration,
            ownerOrgId = chore.ownerOrgId,
        ),
        plugins = sparse(
            Describable(chore.description),
            Taggable(chore.labels),
            Prioritizable(chore.priority, chore.pinned),
            Anchor.Deadline(chore.completeBy, chore.deadlineTimeOfDay),
            Targeted(chore.targetDate),
            // The one kind carrying a cadence mode. It is non-null on a Chore — an absent wire token
            // IS `Rolling`, the backend's `#[default]` — so this Repeats always says something and
            // always loads, even on a Chore whose rule did not survive the wire.
            Repeats(chore.recurrence, chore.seriesId, chore.series, chore.cadenceMode),
            Progress(Lifecycle.Definition(chore.definitionState)),
            Blocker(chore.blocked, chore.isBlocker),
        ),
    )

    override fun read(event: Event): Item = Item(
        core = Core(
            id = event.id.value,
            orgSlug = event.orgSlug,
            title = event.title,
            parentId = event.parentId?.value,
            sequence = event.sequence,
            ref = event.ref,
            dateCreated = event.dateCreated,
            deletedAt = event.deletedAt,
            hydration = event.hydration,
            ownerOrgId = event.ownerOrgId,
        ),
        plugins = sparse(
            Describable(event.description),
            Taggable(event.labels),
            Prioritizable(event.priority, event.pinned),
            appointment(event),
            Targeted(event.targetDate),
            Repeats(event.recurrence, event.seriesId, event.series),
            Progress(Lifecycle.Definition(event.definitionState)),
            Blocker(event.blocked, event.isBlocker),
        ),
    )

    // ── Write: a plugin list becomes a kind row again ──────────────────────────────────────────

    override fun writeTask(item: Item): Task {
        val core = item.core
        val deadline = item.anchorAsDeadline()
        return Task(
            id = TaskId(core.id),
            orgSlug = core.orgSlug,
            title = core.title,
            workingState = item.progress.lifecycle.workingStateOrDefault(),
            labels = item.taggable.labels,
            parentId = core.parentId?.let(::TaskId),
            children = core.childIds.map(::TaskId),
            completeBy = deadline.completeBy,
            deadlineTimeOfDay = deadline.timeOfDay,
            targetDate = item.targeted.targetDate,
            priority = item.priority.priority,
            productive = item.trackable.productive,
            desire = item.volition.desire,
            pinned = item.priority.pinned,
            sequence = core.sequence,
            ref = core.ref,
            dateCreated = core.dateCreated,
            finishedAt = item.progress.finishedAt,
            deletedAt = core.deletedAt,
            hydration = core.hydration,
            ownerOrgId = core.ownerOrgId,
            description = item.describable.description,
            nextTaskId = item.succeeds.nextId?.let(::TaskId),
            descendantDone = core.descendantDone,
            descendantTotal = core.descendantTotal,
            blocked = item.blocker.blocked,
            isBlocker = item.blocker.isBlocker,
            blockedBy = item.blocker.blockedBy,
            external = item.importable.external,
            attachmentCount = item.attachable.attachmentCount,
            attachmentTotalSize = item.attachable.attachmentTotalSize,
        )
    }

    override fun writeHabit(item: Item): Habit {
        val core = item.core
        val deadline = item.anchorAsDeadline()
        return Habit(
            id = HabitId(core.id),
            orgSlug = core.orgSlug,
            title = core.title,
            definitionState = item.progress.lifecycle.definitionStateOrDefault(),
            recurrence = item.repeats.recurrence,
            labels = item.taggable.labels,
            parentId = core.parentId?.let(::TaskId),
            completeBy = deadline.completeBy,
            deadlineTimeOfDay = deadline.timeOfDay,
            targetDate = item.targeted.targetDate,
            priority = item.priority.priority,
            pinned = item.priority.pinned,
            sequence = core.sequence,
            ref = core.ref,
            dateCreated = core.dateCreated,
            deletedAt = core.deletedAt,
            hydration = core.hydration,
            ownerOrgId = core.ownerOrgId,
            description = item.describable.description,
            seriesId = item.repeats.seriesId,
            series = item.repeats.series,
            blocked = item.blocker.blocked,
            isBlocker = item.blocker.isBlocker,
        )
    }

    override fun writeChore(item: Item): Chore {
        val core = item.core
        val deadline = item.anchorAsDeadline()
        return Chore(
            id = ChoreId(core.id),
            orgSlug = core.orgSlug,
            title = core.title,
            definitionState = item.progress.lifecycle.definitionStateOrDefault(),
            recurrence = item.repeats.recurrence,
            // `null` here means "the plugin list said nothing about a cadence mode", and a Chore's
            // wire default for exactly that is Rolling. Not a guess — the backend's `#[default]`.
            cadenceMode = item.repeats.cadenceMode ?: CadenceMode.Rolling,
            labels = item.taggable.labels,
            parentId = core.parentId?.let(::TaskId),
            completeBy = deadline.completeBy,
            deadlineTimeOfDay = deadline.timeOfDay,
            targetDate = item.targeted.targetDate,
            priority = item.priority.priority,
            pinned = item.priority.pinned,
            sequence = core.sequence,
            ref = core.ref,
            dateCreated = core.dateCreated,
            deletedAt = core.deletedAt,
            hydration = core.hydration,
            ownerOrgId = core.ownerOrgId,
            description = item.describable.description,
            seriesId = item.repeats.seriesId,
            series = item.repeats.series,
            blocked = item.blocker.blocked,
            isBlocker = item.blocker.isBlocker,
        )
    }

    override fun writeEvent(item: Item): Event {
        val core = item.core
        val appointment = item.anchor as? Anchor.Appointment ?: Anchor.Appointment()
        return Event(
            id = EventId(core.id),
            orgSlug = core.orgSlug,
            title = core.title,
            definitionState = item.progress.lifecycle.definitionStateOrDefault(),
            recurrence = item.repeats.recurrence,
            allDay = appointment.allDayFlag,
            completeBy = appointment.start,
            endTime = appointment.end,
            startTimeOfDay = appointment.startTimeOfDay,
            endTimeOfDay = appointment.endTimeOfDay,
            targetDate = item.targeted.targetDate,
            priority = item.priority.priority,
            labels = item.taggable.labels,
            parentId = core.parentId?.let(::TaskId),
            pinned = item.priority.pinned,
            sequence = core.sequence,
            ref = core.ref,
            dateCreated = core.dateCreated,
            deletedAt = core.deletedAt,
            hydration = core.hydration,
            ownerOrgId = core.ownerOrgId,
            description = item.describable.description,
            seriesId = item.repeats.seriesId,
            series = item.repeats.series,
            blocked = item.blocker.blocked,
            isBlocker = item.blocker.isBlocker,
        )
    }

    // ── Sparseness ─────────────────────────────────────────────────────────────────────────────

    /**
     * Keep only the plugins that say something — rule 2 in the class KDoc.
     *
     * Each plugin answers for itself through [Plugin.saysSomething], so there is no `when` over plugin
     * types here and no table a new Family can be forgotten from. [Plugin.degenerate] is abstract, so
     * a Family that lands without deciding what its silence means does not compile.
     */
    private fun sparse(vararg plugins: Plugin): List<Plugin> = plugins.filter { it.saysSomething }

    // ── Small readers the four directions share ────────────────────────────────────────────────

    /**
     * The window for the one kind whose instant is a **start**.
     *
     * An Event's `completeBy` is the moment it *begins*, under the same wire field name that means a
     * deadline on the other three kinds. It crosses unchanged, with no conversion between the two
     * claims. Splitting [Anchor.Deadline] from [Anchor.Appointment] is what gives the two claims
     * separate names; `TemporalConflationTest` pins the reproduction as deliberate.
     *
     * Dropped by [sparse] when the Event says nothing about time at all — including the `all_day`
     * flag, which is a stored column here rather than the derived reading it is on the server.
     */
    private fun appointment(event: Event): Anchor = Anchor.Appointment(
        start = event.completeBy,
        end = event.endTime,
        startTimeOfDay = event.startTimeOfDay,
        endTimeOfDay = event.endTimeOfDay,
        allDayFlag = event.allDay,
    )

    /** This item's anchor read as a deadline, or an empty one — for the three kinds that have one. */
    private fun Item.anchorAsDeadline(): Anchor.Deadline =
        anchor as? Anchor.Deadline ?: Anchor.Deadline()

    /**
     * A Task's lifecycle, or the wire default.
     *
     * `Open` rather than a thrown error: an [Item] carrying no [Progress] is a legal plugin shape, and
     * `Open` is what a Task row with no status decodes to. Being total is what lets the write
     * direction stay a pure function.
     */
    private fun Lifecycle.workingStateOrDefault(): WorkingState =
        (this as? Lifecycle.Working)?.state ?: WorkingState.Open

    /** A recurring definition's lifecycle, or the wire default. Same reasoning as the Task's. */
    private fun Lifecycle.definitionStateOrDefault(): DefinitionState =
        (this as? Lifecycle.Definition)?.state ?: DefinitionState.Active
}

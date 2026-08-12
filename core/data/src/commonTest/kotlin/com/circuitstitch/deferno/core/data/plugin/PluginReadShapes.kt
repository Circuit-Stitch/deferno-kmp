package com.circuitstitch.deferno.core.data.plugin

import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.recipe.KindRow
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import com.circuitstitch.deferno.core.model.Item as TreeRow

/**
 * The corpus and the two reconstructions behind the read facade's sufficiency gate (#421).
 * `PluginReadParityTest` runs them and explains what the gate is for.
 *
 * [asTreeRow] and [asRecurringDefinition] rebuild the two shipped projections, [TreeRow] and
 * [RecurringDefinition], from a plugin read alone. They live here rather than in `commonMain` because
 * nothing yet calls them; Phase 4 lifts one into production if a surface wants it.
 */
internal object PluginReadShapes {

    val CREATED: Instant = Instant.parse("2026-04-02T09:15:00Z")
    val CURSOR: Instant = Instant.parse("2026-08-14T17:00:00Z")
    val TARGET: Instant = Instant.parse("2026-08-12T00:00:00Z")
    val FINISHED: Instant = Instant.parse("2026-08-11T20:41:03Z")

    val RULE = Recurrence(Cadence.Weekly(listOf("TU")), RecurrenceBound.AfterCount(12))
    val INPUTS = SeriesInputs(
        anchorLocal = LocalDateTime(2026, 4, 2, 9, 0),
        tzid = "America/Los_Angeles",
        exdates = listOf(LocalDateTime(2026, 5, 5, 9, 0)),
    )

    private val GITHUB = ExternalRef(ItemSource.GitHub, "circuit-stitch/deferno#42", "https://example.invalid/42")

    /**
     * One row of the corpus: a wire row, and the label a failure names it by. [KindRow] is the recipe
     * layer's own "one of the four", so it carries the [ItemKind] both reconstructions need.
     */
    data class Shape(val label: String, val row: KindRow) {
        val kind: ItemKind get() = row.kind
    }

    /**
     * Every kind, spanning between them every field either shipped projection carries.
     *
     * Wide rather than a cross product. `core:model` already runs the combinatorial corpus against the
     * round-trip gate, so repeating it here would re-test the recipe rather than the facade. What this
     * corpus must guarantee is **non-vacuity**, which `PluginReadParityTest` asserts field by field.
     */
    val ALL: List<Shape> = listOf(
        Shape("task/full", KindRow.OfTask(fullTask())),
        Shape("task/bare", KindRow.OfTask(bareTask())),
        Shape("task/done", KindRow.OfTask(fullTask().copy(workingState = WorkingState.Done, finishedAt = FINISHED))),
        Shape("task/imported", KindRow.OfTask(bareTask().copy(external = GITHUB))),
        Shape("habit/live-series", KindRow.OfHabit(fullHabit())),
        Shape("habit/exhausted", KindRow.OfHabit(fullHabit().copy(completeBy = null))),
        Shape("habit/archived", KindRow.OfHabit(fullHabit().copy(definitionState = DefinitionState.Archived))),
        Shape("chore/rolling", KindRow.OfChore(fullChore())),
        Shape("chore/fixed", KindRow.OfChore(fullChore().copy(cadenceMode = CadenceMode.Fixed))),
        Shape("chore/ruleless", KindRow.OfChore(fullChore().copy(recurrence = null, series = null, seriesId = null))),
        Shape("event/timed", KindRow.OfEvent(fullEvent())),
        Shape("event/all-day", KindRow.OfEvent(fullEvent().copy(allDay = true, startTimeOfDay = null, endTimeOfDay = null))),
        Shape("event/in-review", KindRow.OfEvent(fullEvent().copy(definitionState = DefinitionState.InReview))),
    )

    // A Task carrying every field the tree row reads off one: a parent, a place in the order, both
    // subtree counts, both dependency flags plus the edge list itself, and a full hydration.
    private fun fullTask() = Task(
        id = TaskId("task-full"),
        orgSlug = "u-e4h2qk",
        title = "Draft the migration note",
        workingState = WorkingState.InProgress,
        labels = listOf("writing", "migration"),
        parentId = TaskId("habit-full"),
        children = listOf(TaskId("task-bare")),
        completeBy = CURSOR,
        deadlineTimeOfDay = LocalTime(17, 0),
        targetDate = TARGET,
        priority = Priority.Fire,
        productive = 0.75,
        desire = 0.25,
        pinned = true,
        sequence = 10,
        ref = "u-e4h2qk-101",
        dateCreated = CREATED,
        hydration = HydrationState.Full,
        ownerOrgId = OrgId("org-1"),
        description = "The one that explains the recipe layer.",
        nextTaskId = TaskId("task-bare"),
        descendantDone = 3,
        descendantTotal = 7,
        blocked = true,
        isBlocker = true,
        blockedBy = listOf(BlockedByRef("task-bare"), BlockedByRef("event-full", occurrence = "2026-08-20")),
        attachmentCount = 2,
        attachmentTotalSize = 4096,
    )

    // The other end: a Task saying as little as a row can. Every optional field at its degenerate
    // value, so the facade is exercised on a plugin list that is nearly empty as well as on a full one.
    private fun bareTask() = Task(
        id = TaskId("task-bare"),
        orgSlug = "u-e4h2qk",
        title = "Buy milk",
        workingState = WorkingState.Open,
        sequence = 20,
        dateCreated = CREATED,
    )

    private fun fullHabit() = Habit(
        id = HabitId("habit-full"),
        orgSlug = "u-e4h2qk",
        title = "Take the bins out",
        definitionState = DefinitionState.Active,
        recurrence = RULE,
        labels = listOf("home"),
        parentId = TaskId("task-bare"),
        completeBy = CURSOR,
        deadlineTimeOfDay = LocalTime(8, 30),
        targetDate = TARGET,
        priority = Priority.Backlog,
        pinned = true,
        sequence = 30,
        ref = "u-e4h2qk-102",
        dateCreated = CREATED,
        hydration = HydrationState.Full,
        ownerOrgId = OrgId("org-1"),
        description = "Tuesday night, before the truck.",
        seriesId = "series-habit",
        series = INPUTS,
        blocked = true,
        isBlocker = false,
    )

    private fun fullChore() = Chore(
        id = ChoreId("chore-full"),
        orgSlug = "u-e4h2qk",
        title = "Water the plants",
        definitionState = DefinitionState.Active,
        recurrence = RULE,
        cadenceMode = CadenceMode.Rolling,
        labels = listOf("home", "plants"),
        parentId = null,
        completeBy = CURSOR,
        deadlineTimeOfDay = LocalTime(19, 0),
        targetDate = TARGET,
        priority = Priority.Normal,
        sequence = 40,
        ref = "u-e4h2qk-103",
        dateCreated = CREATED,
        description = "The ferns die first.",
        seriesId = "series-chore",
        series = INPUTS,
        blocked = false,
        isBlocker = true,
    )

    private fun fullEvent() = Event(
        id = EventId("event-full"),
        orgSlug = "u-e4h2qk",
        title = "Standup",
        definitionState = DefinitionState.Active,
        recurrence = RULE,
        allDay = false,
        completeBy = CURSOR,
        endTime = CURSOR.plus(kotlin.time.Duration.parse("30m")),
        startTimeOfDay = LocalTime(9, 30),
        endTimeOfDay = LocalTime(10, 0),
        targetDate = TARGET,
        priority = Priority.Normal,
        labels = listOf("work"),
        parentId = TaskId("task-full"),
        sequence = 50,
        ref = "u-e4h2qk-104",
        dateCreated = CREATED,
        hydration = HydrationState.Full,
        description = "Ten minutes, standing.",
        seriesId = "series-event",
        series = INPUTS,
    )
}

/**
 * The shipped tree row, rebuilt from the plugin read alone — the sufficiency claim for
 * [com.circuitstitch.deferno.core.data.item.ItemRepository]'s consumers.
 *
 * [kind] is a parameter for two reasons. [TreeRow] has a `kind` field and the plugin model has none,
 * which is by design. The cursor is the second, and that one is a gap: see
 * [completeByWhereverItLanded].
 */
internal fun Item.asTreeRow(kind: ItemKind): TreeRow = TreeRow(
    id = core.id,
    kind = kind,
    title = core.title,
    parentId = core.parentId,
    sequence = core.sequence,
    // A Done/Dropped Task or an Archived definition — the one point the two lifecycles agree, which
    // `Lifecycle` already derives. The shipped mappers state it twice, once per arm.
    isTerminal = progress.lifecycle.isTerminal,
    descendantDone = core.descendantDone,
    descendantTotal = core.descendantTotal,
    source = importable.external?.source,
    externalRef = importable.external?.id,
    blocked = blocker.blocked,
    isBlocker = blocker.isBlocker,
    blockedBy = blocker.blockedBy,
    definitionState = (progress.lifecycle as? Lifecycle.Definition)?.state,
    recurrence = repeats.recurrence,
    // The kind is load-bearing here, and that is a finding rather than an inconvenience. See
    // [completeByWhereverItLanded].
    recurrenceCursorAt = if (kind == ItemKind.Task) null else completeByWhereverItLanded(),
    seriesId = repeats.seriesId,
    series = repeats.series,
)

/**
 * The wire's `complete_by`, out of whichever [Anchor] member the parity recipe put it in. This is the
 * one place the plugin read is **not** sufficient on its own.
 *
 * That field means a deadline on a Task, a recurrence cursor on a Habit or Chore, and a start on an
 * Event. [Anchor] splits off only the start, as [Anchor.Appointment]. The other two both land in
 * [Anchor.Deadline] with no field between them, so the kind is the only discriminator left.
 *
 * Reproducing that is correct for a **parity** recipe, since the storage genuinely is one column. The
 * full argument is in ADR-0056, and closing it is #439.
 * `theRecurrenceCursorIsIndistinguishableFromADeadline` pins the gap meanwhile.
 */
internal fun Item.completeByWhereverItLanded(): Instant? = when (val held = anchor) {
    is Anchor.Deadline -> held.completeBy
    is Anchor.Appointment -> held.start
    Anchor.Unanchored -> null
}

/**
 * The shipped kind-neutral definition read, rebuilt the same way, for
 * [com.circuitstitch.deferno.core.data.definition.DefinitionRepository]'s consumers. Only meaningful
 * for the three recurring kinds — a Task is not a definition, and `toDefinition` has no Task arm.
 */
internal fun Item.asRecurringDefinition(kind: ItemKind): RecurringDefinition = RecurringDefinition(
    id = core.id,
    kind = kind,
    title = core.title,
    definitionState = (progress.lifecycle as? Lifecycle.Definition)?.state ?: DefinitionState.Active,
    description = describable.description,
    labels = taggable.labels,
    recurrence = repeats.recurrence,
    // The same conflation, on a surface that only ever holds recurring kinds. Here the anchor is the
    // cursor whichever member holds it, so no kind branch is needed.
    cursorAt = completeByWhereverItLanded(),
    seriesId = repeats.seriesId,
    series = repeats.series,
    parentId = core.parentId?.let(::TaskId),
    ref = core.ref,
    hydration = core.hydration,
    blocked = blocker.blocked,
    isBlocker = blocker.isBlocker,
)

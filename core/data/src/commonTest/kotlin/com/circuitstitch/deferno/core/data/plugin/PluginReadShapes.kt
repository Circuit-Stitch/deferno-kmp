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
 * The corpus the read facade's **sufficiency gate** runs over, and the two reconstructions it proves
 * (#421).
 *
 * Round-trip identity is Phase 0's gate and it is green: a kind row read into plugins and written back
 * is the same row. The risk this phase adds is a different one. The plugin read is *readable alongside*
 * two shipped projections — [TreeRow] and [RecurringDefinition] — and if the two readings disagree
 * about the same cached row, Phase 4 changes what a surface renders while claiming to re-model it.
 *
 * So the gate asks the question that actually blocks Phase 4: **is the plugin read sufficient?** Can
 * each shipped projection be rebuilt from it alone, field for field. [asTreeRow] and
 * [asRecurringDefinition] are those rebuilds, and `PluginReadParityTest` asserts each equals what the
 * shipped mapper produces from the same row.
 *
 * They live here rather than in `commonMain` deliberately. A bridge with no consumer is production code
 * nothing calls; Phase 4 lifts one if a surface wants it, and until then this is a gate rather than a
 * seam.
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
     * One row of the corpus: a wire row, and the label a failure names it by.
     *
     * [KindRow] rather than a bespoke union — it is the recipe layer's own "one of the four", it
     * carries the [ItemKind] the two reconstructions need, and it dies with the recipes at the cutover.
     */
    data class Shape(val label: String, val row: KindRow) {
        val kind: ItemKind get() = row.kind
    }

    /**
     * Every kind, spanning between them every field either shipped projection carries.
     *
     * Wide rather than a cross product: the round-trip gate in `core:model` already runs the
     * combinatorial corpus, and repeating it here would re-test the recipe rather than the facade. What
     * this corpus has to guarantee instead is **non-vacuity** — that no projection field passes the
     * gate by being null on every row — which `PluginReadParityTest` asserts field by field rather than
     * leaving to the reader to eyeball.
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
 * [com.circuitstitch.deferno.core.data.item.ItemRepository]'s consumers, stated as a function so it can
 * be checked against the mapper that ships.
 *
 * [kind] is a parameter because [TreeRow] has a `kind` field and nothing in the plugin model does; that
 * much is by design and not a gap. The **cursor** below is the gap, and it is why this takes the kind
 * for a second reason.
 */
internal fun Item.asTreeRow(kind: ItemKind): TreeRow = TreeRow(
    id = core.id,
    kind = kind,
    title = core.title,
    parentId = core.parentId,
    sequence = core.sequence,
    // The one point the two lifecycles agree, and `Lifecycle` already derives it — a Done/Dropped Task
    // or an Archived definition. The shipped mappers say it twice, once per arm.
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
    // THE KIND IS LOAD-BEARING HERE, and that is a finding rather than an inconvenience. See
    // [completeByWhereverItLanded].
    recurrenceCursorAt = if (kind == ItemKind.Task) null else completeByWhereverItLanded(),
    seriesId = repeats.seriesId,
    series = repeats.series,
)

/**
 * The wire's `complete_by`, out of whichever [Anchor] member the parity recipe put it in — and the one
 * place the plugin read is **not** sufficient on its own.
 *
 * One wire field carries three incompatible claims. On a Task it is a plain deadline. On a Habit or
 * Chore it is a moving recurrence **cursor** — where the series has walked to, never a bound (backend
 * ADR `2026-06-02-recurrence-anchor-and-bound`). On an Event it is a **start**. [Anchor] splits the
 * third off as [Anchor.Appointment], which is what `TemporalConflationTest` pins; the first two both
 * land in [Anchor.Deadline] and nothing distinguishes them.
 *
 * The shipped projection is emphatic about keeping the first two apart: it names its field
 * `recurrenceCursorAt` rather than `completeBy`, projects it on the recurring kinds only, and its KDoc
 * says conflating the two "would make every dated Task read as an exhausted-or-due series". So the
 * caller here has to supply the kind to get that distinction back — which means a Phase 4 atom
 * rendering "due by" off `anchor` would render a Habit's cursor as a deadline.
 *
 * Reproducing it is correct for a **parity** recipe: the storage genuinely is one field, and correcting
 * it is a target-recipe change with its own issue, exactly as ADR-0056 says of the Event half. The
 * shape of the fix is already known, because `Anchor` split the other claim the same way. Pinned as
 * deliberate by `PluginReadParityTest.theRecurrenceCursorIsIndistinguishableFromADeadline` so nobody
 * later reads the reproduction as an oversight.
 */
internal fun Item.completeByWhereverItLanded(): Instant? = when (val held = anchor) {
    is Anchor.Deadline -> held.completeBy
    is Anchor.Appointment -> held.start
    Anchor.Unanchored -> null
}

/**
 * The shipped kind-neutral definition read, rebuilt from the plugin read alone — the same claim for
 * [com.circuitstitch.deferno.core.data.definition.DefinitionRepository]'s consumers.
 *
 * Only meaningful for the three recurring kinds; a Task is not a definition, and the shipped
 * `toDefinition` has no Task arm either.
 */
internal fun Item.asRecurringDefinition(kind: ItemKind): RecurringDefinition = RecurringDefinition(
    id = core.id,
    kind = kind,
    title = core.title,
    definitionState = (progress.lifecycle as? Lifecycle.Definition)?.state ?: DefinitionState.Active,
    description = describable.description,
    labels = taggable.labels,
    recurrence = repeats.recurrence,
    // The same conflation, read on a surface that only ever holds recurring kinds — so here the anchor
    // IS the cursor whichever member holds it, and no kind branch is needed.
    cursorAt = completeByWhereverItLanded(),
    seriesId = repeats.seriesId,
    series = repeats.series,
    parentId = core.parentId?.let(::TaskId),
    ref = core.ref,
    hydration = core.hydration,
    blocked = blocker.blocked,
    isBlocker = blocker.isBlocker,
)

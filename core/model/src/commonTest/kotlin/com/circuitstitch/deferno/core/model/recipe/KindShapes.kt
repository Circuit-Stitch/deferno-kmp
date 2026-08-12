package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.BlockedByRef
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
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.SeriesOverride
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * The **kind × field-combination corpus** the two Phase-0 harnesses sweep — one place that says what
 * "every shape the four-kind wire can carry" means, so `KindRecipeRoundTripTest` and
 * `BehaviourParityTest` are gated on the same set rather than on two hand-listed samples that drift.
 *
 * ### The combination scheme, and what it is honestly claiming
 *
 * A full cartesian product over every optional field would be 2^12 shapes for a Task alone, so this
 * is deliberately **not** that. It is the product taken at the granularity ADR-0055 argues is the
 * real one:
 *
 *  - **Within a [Family][com.circuitstitch.deferno.core.model.plugin.Plugin], full product.** The
 *    fields that map into one family interact — `completeBy` + `deadlineTimeOfDay` become one
 *    `Anchor`, `workingState` + `finishedAt` become one progress record — so every combination of
 *    them is generated.
 *  - **Across families, three baselines.** Each family's product is applied on top of a *minimal*
 *    row (everything absent), a *saturated* row (every other field at a non-default value) and a
 *    *tombstoned* row. The saturated baseline is the one with teeth: it is what fails when writing
 *    one family back to the wire clobbers a field belonging to another.
 *
 * What this does **not** claim to cover is a three-way interaction between fields in three different
 * families. ADR-0055's central claim is that the eight axes are independent, so such an interaction
 * would be a defect in the cut rather than a case this corpus should be enumerating around — and it
 * would show up here as a saturated-baseline failure regardless.
 *
 * ### A fifth kind cannot be added without being covered
 *
 * [shapesOf] is an exhaustive `when` over [ItemKind], the same mechanism `core:domain`'s
 * `sampleCommand` uses: a new kind is a **compile error** here, not a silently smaller corpus. The
 * counts in `KindRecipeRoundTripTest` are the guard in the other direction — a corpus that quietly
 * shrinks fails rather than passing faster.
 */
internal object KindShapes {

    /** Every shape in the corpus, all four kinds, in a stable order. */
    val ALL: List<KindShape> by lazy { ItemKind.entries.flatMap(::shapesOf) }

    /**
     * Every shape of one kind. **Exhaustive over [ItemKind]** — see the class KDoc.
     *
     * Each arm is `baselines × the union of that kind's per-family products`, labelled
     * `<kind>/<baseline>/<family>:<axis values>` so a failure names the shape that produced it and
     * `KindRecipeRoundTripTest` can assert every declared axis actually reached the corpus.
     */
    fun shapesOf(kind: ItemKind): List<KindShape> = when (kind) {
        ItemKind.Task -> TaskShapes.all()
        ItemKind.Habit -> HabitShapes.all()
        ItemKind.Chore -> ChoreShapes.all()
        ItemKind.Event -> EventShapes.all()
    }
}

/**
 * One shape in the corpus: a [label] naming how it was built, and the kind row itself.
 *
 * Sealed rather than a `kind` + `Any` pair, so a harness that reads the row gets it typed, and so
 * adding a kind breaks every reader that must handle it — the same seal argument the plugin
 * hierarchy makes.
 */
internal sealed interface KindShape {
    val label: String
    val kind: ItemKind

    data class OfTask(override val label: String, val task: Task) : KindShape {
        override val kind get() = ItemKind.Task
    }

    data class OfHabit(override val label: String, val habit: Habit) : KindShape {
        override val kind get() = ItemKind.Habit
    }

    data class OfChore(override val label: String, val chore: Chore) : KindShape {
        override val kind get() = ItemKind.Chore
    }

    data class OfEvent(override val label: String, val event: Event) : KindShape {
        override val kind get() = ItemKind.Event
    }
}

// ── The combination machinery ──────────────────────────────────────────────────────────────────

/** One named value of one field, as a change applied to a row. */
internal data class Choice<T>(val name: String, val apply: (T) -> T)

/** One field's alternatives. The first is the row's own baseline value and is applied like any other. */
internal typealias Axis<T> = List<Choice<T>>

/** A [Family][com.circuitstitch.deferno.core.model.plugin.Plugin]'s fields, producted together. */
internal data class Group<T>(val family: String, val axes: List<Axis<T>>)

/**
 * `baselines × ⋃ (product within each group)`, labelled.
 *
 * The product is taken **inside** a group and never across groups — see [KindShapes]'s KDoc for why
 * that is the granularity rather than a shortcut around one.
 */
internal fun <T> combinations(
    baselines: List<Choice<T>>,
    seed: T,
    groups: List<Group<T>>,
): List<Pair<String, T>> = baselines.flatMap { baseline ->
    val base = baseline.apply(seed)
    groups.flatMap { group ->
        product(group.axes).map { picks ->
            val label = "${baseline.name}/${group.family}:" + picks.joinToString("+") { it.name }
            label to picks.fold(base) { row, choice -> choice.apply(row) }
        }
    }
}

/** The cartesian product of a group's axes, as lists of picks in axis order. */
private fun <T> product(axes: List<Axis<T>>): List<List<Choice<T>>> =
    axes.fold(listOf(emptyList())) { acc, axis -> acc.flatMap { picks -> axis.map { picks + it } } }

// ── Shared fixture values ──────────────────────────────────────────────────────────────────────
//
// Fixed literals, never a clock read: a corpus whose contents depend on when it runs cannot pin a
// round trip. Same rule the recurrence corpus states — the cases are generated, not sampled.

private val created = Instant.parse("2026-01-05T08:00:00Z")
private val deadline = Instant.parse("2026-03-01T17:00:00Z")
private val target = Instant.parse("2026-02-20T00:00:00Z")
private val finished = Instant.parse("2026-02-28T09:30:00Z")
private val endsAt = Instant.parse("2026-03-01T18:30:00Z")
private val deleted = Instant.parse("2026-04-01T00:00:00Z")
private val fivePm = LocalTime(17, 0)
private val sixThirty = LocalTime(18, 30)

private val everyDay = Recurrence(cadence = Cadence.Daily)
private val everyOtherDayUntil = Recurrence(
    cadence = Cadence.EveryNDays(2),
    bound = RecurrenceBound.OnDate(kotlinx.datetime.LocalDate(2026, 12, 31)),
)
private val seriesInputs = SeriesInputs(
    anchorLocal = LocalDateTime.parse("2026-01-05T09:00:00"),
    tzid = "America/Los_Angeles",
    untilUtc = null,
    exdates = listOf(LocalDateTime.parse("2026-01-12T09:00:00")),
    overrides = listOf(
        SeriesOverride(
            recurrenceId = LocalDateTime.parse("2026-01-19T09:00:00"),
            movedToLocal = LocalDateTime.parse("2026-01-20T09:00:00"),
        ),
    ),
)
private val github = ExternalRef(ItemSource.GitHub, "Circuit-Stitch/deferno-kmp#417", "https://example.invalid/417")
private val blockedByOne = listOf(BlockedByRef(item = "b1e7a2c4-0000-4000-8000-000000000001"))

// ── Task ───────────────────────────────────────────────────────────────────────────────────────

private object TaskShapes {

    private val seed = Task(
        id = TaskId("11111111-0000-4000-8000-000000000001"),
        orgSlug = "u-e4h2qk",
        title = "Renew the passport",
        workingState = WorkingState.Open,
        dateCreated = created,
    )

    private val baselines = listOf(
        Choice<Task>("minimal") { it },
        Choice("saturated") {
            it.copy(
                labels = listOf("admin", "travel"),
                parentId = TaskId("11111111-0000-4000-8000-0000000000ff"),
                children = listOf(TaskId("11111111-0000-4000-8000-000000000002")),
                completeBy = deadline,
                deadlineTimeOfDay = fivePm,
                targetDate = target,
                priority = Priority.Fire,
                productive = 0.75,
                desire = 0.5,
                pinned = true,
                sequence = 417,
                ref = "u-e4h2qk-417",
                hydration = HydrationState.Full,
                ownerOrgId = OrgId("org-1"),
                description = "at the passport office",
                nextTaskId = TaskId("11111111-0000-4000-8000-000000000003"),
                descendantDone = 1,
                descendantTotal = 3,
                blocked = true,
                isBlocker = true,
                blockedBy = blockedByOne,
                external = github,
                attachmentCount = 2,
                attachmentTotalSize = 4096,
            )
        },
        Choice("tombstoned") { it.copy(deletedAt = deleted) },
    )

    private val groups = listOf(
        Group(
            "content",
            listOf(
                listOf(
                    Choice<Task>("no-desc") { it.copy(description = null) },
                    Choice("desc") { it.copy(description = "at the passport office") },
                ),
                listOf(
                    Choice("no-labels") { it.copy(labels = emptyList()) },
                    Choice("labels") { it.copy(labels = listOf("admin", "travel")) },
                ),
                listOf(
                    Choice("normal") { it.copy(priority = Priority.Normal, pinned = false) },
                    Choice("fire-pinned") { it.copy(priority = Priority.Fire, pinned = true) },
                    Choice("backlog") { it.copy(priority = Priority.Backlog, pinned = false) },
                ),
                listOf(
                    Choice("no-attachments") { it.copy(attachmentCount = 0, attachmentTotalSize = 0) },
                    Choice("attachments") { it.copy(attachmentCount = 2, attachmentTotalSize = 4096) },
                ),
            ),
        ),
        Group(
            "temporal",
            listOf(
                // The three anchor shapes a Task can be in. `deadlineTimeOfDay` without a
                // `completeBy` is not generated: the day comes from `completeBy`, so a time with no
                // day names no instant.
                listOf(
                    Choice<Task>("unanchored") { it.copy(completeBy = null, deadlineTimeOfDay = null) },
                    Choice("all-day-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = null) },
                    Choice("timed-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = fivePm) },
                ),
                listOf(
                    Choice("no-target") { it.copy(targetDate = null) },
                    Choice("target") { it.copy(targetDate = target) },
                ),
            ),
        ),
        Group(
            "enactment",
            listOf(
                // `finishedAt` is producted against every state on purpose: the wire can carry a
                // finish timestamp on a row that is not Done, and the round trip has to survive it
                // rather than tidy it away.
                WorkingState.entries.map { state -> Choice<Task>(state.name) { it.copy(workingState = state) } },
                listOf(
                    Choice("unfinished") { it.copy(finishedAt = null) },
                    Choice("finished") { it.copy(finishedAt = finished) },
                ),
                listOf(
                    Choice("no-productive") { it.copy(productive = null) },
                    Choice("productive") { it.copy(productive = 0.75) },
                ),
            ),
        ),
        Group(
            "modal",
            listOf(
                // `desire` is a continuous Double? on this client and flows through the ADR-0041
                // backup mappers, so the corpus carries the values that bucketing to three would
                // destroy — 0.0 is a claim, and it is not `null`.
                listOf(
                    Choice<Task>("no-desire") { it.copy(desire = null) },
                    Choice("desire-0") { it.copy(desire = 0.0) },
                    Choice("desire-mid") { it.copy(desire = 0.5) },
                    Choice("desire-1") { it.copy(desire = 1.0) },
                ),
            ),
        ),
        Group(
            "linkage",
            listOf(
                listOf(
                    Choice<Task>("unblocked") { it.copy(blocked = false, isBlocker = false, blockedBy = emptyList()) },
                    Choice("blocked") { it.copy(blocked = true, isBlocker = false, blockedBy = blockedByOne) },
                    Choice("is-blocker") { it.copy(blocked = false, isBlocker = true, blockedBy = emptyList()) },
                ),
                listOf(
                    Choice("native") { it.copy(external = null) },
                    Choice("imported") { it.copy(external = github) },
                ),
                listOf(
                    Choice("no-successor") { it.copy(nextTaskId = null) },
                    Choice("successor") { it.copy(nextTaskId = TaskId("11111111-0000-4000-8000-000000000003")) },
                ),
            ),
        ),
    )

    fun all(): List<KindShape> = combinations(baselines, seed, groups)
        .map { (label, task) -> KindShape.OfTask("Task/$label", task) }
}

// ── Habit ──────────────────────────────────────────────────────────────────────────────────────

private object HabitShapes {

    private val seed = Habit(
        id = HabitId("22222222-0000-4000-8000-000000000001"),
        orgSlug = "u-e4h2qk",
        title = "Practice scales",
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private val baselines = listOf(
        Choice<Habit>("minimal") { it },
        Choice("saturated") {
            it.copy(
                recurrence = everyDay,
                labels = listOf("music"),
                parentId = TaskId("11111111-0000-4000-8000-0000000000ff"),
                completeBy = deadline,
                deadlineTimeOfDay = fivePm,
                targetDate = target,
                priority = Priority.Fire,
                pinned = true,
                sequence = 418,
                ref = "u-e4h2qk-418",
                hydration = HydrationState.Full,
                ownerOrgId = OrgId("org-1"),
                description = "twenty minutes",
                seriesId = "series-h1",
                series = seriesInputs,
                blocked = true,
                isBlocker = true,
            )
        },
        Choice("tombstoned") { it.copy(deletedAt = deleted) },
    )

    private val groups = listOf(
        Group(
            "content",
            listOf(
                listOf(
                    Choice<Habit>("no-desc") { it.copy(description = null) },
                    Choice("desc") { it.copy(description = "twenty minutes") },
                ),
                listOf(
                    Choice("no-labels") { it.copy(labels = emptyList()) },
                    Choice("labels") { it.copy(labels = listOf("music")) },
                ),
                listOf(
                    Choice("normal") { it.copy(priority = Priority.Normal, pinned = false) },
                    Choice("fire-pinned") { it.copy(priority = Priority.Fire, pinned = true) },
                    Choice("backlog") { it.copy(priority = Priority.Backlog, pinned = false) },
                ),
            ),
        ),
        Group("temporal", listOf(definitionAnchorAxis(), definitionTargetAxis())),
        Group(
            "unfolding",
            listOf(
                recurrenceAxis(),
                seriesAxis(),
                DefinitionState.entries.map { s -> Choice<Habit>(s.name) { it.copy(definitionState = s) } },
            ),
        ),
        Group(
            "linkage",
            listOf(
                listOf(
                    Choice<Habit>("unblocked") { it.copy(blocked = false, isBlocker = false) },
                    Choice("blocked") { it.copy(blocked = true, isBlocker = false) },
                    Choice("is-blocker") { it.copy(blocked = false, isBlocker = true) },
                ),
            ),
        ),
    )

    private fun definitionAnchorAxis(): Axis<Habit> = listOf(
        Choice("unanchored") { it.copy(completeBy = null, deadlineTimeOfDay = null) },
        Choice("all-day-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = null) },
        Choice("timed-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = fivePm) },
    )

    private fun definitionTargetAxis(): Axis<Habit> = listOf(
        Choice("no-target") { it.copy(targetDate = null) },
        Choice("target") { it.copy(targetDate = target) },
    )

    private fun recurrenceAxis(): Axis<Habit> = listOf(
        Choice("no-rule") { it.copy(recurrence = null) },
        Choice("daily") { it.copy(recurrence = everyDay) },
        Choice("every-2-until") { it.copy(recurrence = everyOtherDayUntil) },
    )

    // `series = null` is the wire's ELISION ("this device cannot reproduce that grid"), never an
    // empty grid — so absent and present are two different claims and both are in the corpus.
    private fun seriesAxis(): Axis<Habit> = listOf(
        Choice("elided-series") { it.copy(seriesId = null, series = null) },
        Choice("series-inputs") { it.copy(seriesId = "series-h1", series = seriesInputs) },
    )

    fun all(): List<KindShape> = combinations(baselines, seed, groups)
        .map { (label, habit) -> KindShape.OfHabit("Habit/$label", habit) }
}

// ── Chore ──────────────────────────────────────────────────────────────────────────────────────

private object ChoreShapes {

    private val seed = Chore(
        id = ChoreId("33333333-0000-4000-8000-000000000001"),
        orgSlug = "u-e4h2qk",
        title = "Take the bins out",
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private val baselines = listOf(
        Choice<Chore>("minimal") { it },
        Choice("saturated") {
            it.copy(
                recurrence = everyDay,
                cadenceMode = CadenceMode.Fixed,
                labels = listOf("house"),
                parentId = TaskId("11111111-0000-4000-8000-0000000000ff"),
                completeBy = deadline,
                deadlineTimeOfDay = fivePm,
                targetDate = target,
                priority = Priority.Fire,
                pinned = true,
                sequence = 419,
                ref = "u-e4h2qk-419",
                hydration = HydrationState.Full,
                ownerOrgId = OrgId("org-1"),
                description = "green bin on Tuesdays",
                seriesId = "series-c1",
                series = seriesInputs,
                blocked = true,
                isBlocker = true,
            )
        },
        Choice("tombstoned") { it.copy(deletedAt = deleted) },
    )

    private val groups = listOf(
        Group(
            "content",
            listOf(
                listOf(
                    Choice<Chore>("no-desc") { it.copy(description = null) },
                    Choice("desc") { it.copy(description = "green bin on Tuesdays") },
                ),
                listOf(
                    Choice("no-labels") { it.copy(labels = emptyList()) },
                    Choice("labels") { it.copy(labels = listOf("house")) },
                ),
                listOf(
                    Choice("normal") { it.copy(priority = Priority.Normal, pinned = false) },
                    Choice("fire-pinned") { it.copy(priority = Priority.Fire, pinned = true) },
                    Choice("backlog") { it.copy(priority = Priority.Backlog, pinned = false) },
                ),
            ),
        ),
        Group(
            "temporal",
            listOf(
                listOf(
                    Choice<Chore>("unanchored") { it.copy(completeBy = null, deadlineTimeOfDay = null) },
                    Choice("all-day-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = null) },
                    Choice("timed-deadline") { it.copy(completeBy = deadline, deadlineTimeOfDay = fivePm) },
                ),
                listOf(
                    Choice("no-target") { it.copy(targetDate = null) },
                    Choice("target") { it.copy(targetDate = target) },
                ),
            ),
        ),
        Group(
            "unfolding",
            listOf(
                listOf(
                    Choice<Chore>("no-rule") { it.copy(recurrence = null) },
                    Choice("daily") { it.copy(recurrence = everyDay) },
                    Choice("every-2-until") { it.copy(recurrence = everyOtherDayUntil) },
                ),
                // `cadenceMode` is Chore-only and NON-NULL: an absent wire token is not "unknown",
                // it IS Rolling. Both real modes plus the unmodelled escape hatch are swept.
                listOf(
                    Choice("rolling") { it.copy(cadenceMode = CadenceMode.Rolling) },
                    Choice("fixed") { it.copy(cadenceMode = CadenceMode.Fixed) },
                    Choice("unmodelled-mode") { it.copy(cadenceMode = CadenceMode.Unmodelled("drifting")) },
                ),
                listOf(
                    Choice("elided-series") { it.copy(seriesId = null, series = null) },
                    Choice("series-inputs") { it.copy(seriesId = "series-c1", series = seriesInputs) },
                ),
                DefinitionState.entries.map { s -> Choice<Chore>(s.name) { it.copy(definitionState = s) } },
            ),
        ),
        Group(
            "linkage",
            listOf(
                listOf(
                    Choice<Chore>("unblocked") { it.copy(blocked = false, isBlocker = false) },
                    Choice("blocked") { it.copy(blocked = true, isBlocker = false) },
                    Choice("is-blocker") { it.copy(blocked = false, isBlocker = true) },
                ),
            ),
        ),
    )

    fun all(): List<KindShape> = combinations(baselines, seed, groups)
        .map { (label, chore) -> KindShape.OfChore("Chore/$label", chore) }
}

// ── Event ──────────────────────────────────────────────────────────────────────────────────────

private object EventShapes {

    private val seed = Event(
        id = EventId("44444444-0000-4000-8000-000000000001"),
        orgSlug = "u-e4h2qk",
        title = "Stand-up",
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )

    private val baselines = listOf(
        Choice<Event>("minimal") { it },
        Choice("saturated") {
            it.copy(
                recurrence = everyDay,
                allDay = false,
                completeBy = deadline,
                endTime = endsAt,
                startTimeOfDay = fivePm,
                endTimeOfDay = sixThirty,
                targetDate = target,
                priority = Priority.Fire,
                labels = listOf("work"),
                parentId = TaskId("11111111-0000-4000-8000-0000000000ff"),
                pinned = true,
                sequence = 420,
                ref = "u-e4h2qk-420",
                hydration = HydrationState.Full,
                ownerOrgId = OrgId("org-1"),
                description = "the daily one",
                seriesId = "series-e1",
                series = seriesInputs,
                blocked = true,
                isBlocker = true,
            )
        },
        Choice("tombstoned") { it.copy(deletedAt = deleted) },
    )

    private val groups = listOf(
        Group(
            "content",
            listOf(
                listOf(
                    Choice<Event>("no-desc") { it.copy(description = null) },
                    Choice("desc") { it.copy(description = "the daily one") },
                ),
                listOf(
                    Choice("no-labels") { it.copy(labels = emptyList()) },
                    Choice("labels") { it.copy(labels = listOf("work")) },
                ),
                listOf(
                    Choice("normal") { it.copy(priority = Priority.Normal, pinned = false) },
                    Choice("fire-pinned") { it.copy(priority = Priority.Fire, pinned = true) },
                    Choice("backlog") { it.copy(priority = Priority.Backlog, pinned = false) },
                ),
            ),
        ),
        Group(
            "temporal",
            listOf(
                // THE conflation this migration exists to unsmear, and it is faithfully generated
                // rather than corrected: an Event's `completeBy` is a START, the same field name
                // three other kinds use for a DEADLINE, and `allDay` is server-derived from the two
                // time-of-day fields being null — so the corpus carries `allDay` disagreeing with
                // them too, because the wire can.
                listOf(
                    Choice<Event>("no-window") { it.copy(completeBy = null, endTime = null) },
                    Choice("start-only") { it.copy(completeBy = deadline, endTime = null) },
                    Choice("start-and-end") { it.copy(completeBy = deadline, endTime = endsAt) },
                ),
                listOf(
                    Choice("all-day-times") { it.copy(startTimeOfDay = null, endTimeOfDay = null) },
                    Choice("start-time") { it.copy(startTimeOfDay = fivePm, endTimeOfDay = null) },
                    Choice("both-times") { it.copy(startTimeOfDay = fivePm, endTimeOfDay = sixThirty) },
                ),
                listOf(
                    Choice("all-day-false") { it.copy(allDay = false) },
                    Choice("all-day-true") { it.copy(allDay = true) },
                ),
                listOf(
                    Choice("no-target") { it.copy(targetDate = null) },
                    Choice("target") { it.copy(targetDate = target) },
                ),
            ),
        ),
        Group(
            "unfolding",
            listOf(
                listOf(
                    Choice<Event>("no-rule") { it.copy(recurrence = null) },
                    Choice("daily") { it.copy(recurrence = everyDay) },
                    Choice("every-2-until") { it.copy(recurrence = everyOtherDayUntil) },
                ),
                listOf(
                    Choice("elided-series") { it.copy(seriesId = null, series = null) },
                    Choice("series-inputs") { it.copy(seriesId = "series-e1", series = seriesInputs) },
                ),
                DefinitionState.entries.map { s -> Choice<Event>(s.name) { it.copy(definitionState = s) } },
            ),
        ),
        Group(
            "linkage",
            listOf(
                listOf(
                    Choice<Event>("unblocked") { it.copy(blocked = false, isBlocker = false) },
                    Choice("blocked") { it.copy(blocked = true, isBlocker = false) },
                    Choice("is-blocker") { it.copy(blocked = false, isBlocker = true) },
                ),
            ),
        ),
    )

    fun all(): List<KindShape> = combinations(baselines, seed, groups)
        .map { (label, event) -> KindShape.OfEvent("Event/$label", event) }
}

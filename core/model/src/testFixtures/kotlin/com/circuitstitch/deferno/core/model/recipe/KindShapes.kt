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
import com.circuitstitch.deferno.core.model.plugin.Item
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * The **kind × field-combination corpus** the two Phase-0 harnesses sweep. One place says what
 * "every shape the four-kind wire can carry" means, so `KindRecipeRoundTripTest` and
 * `BehaviourParityTest` gate on the same set rather than on two hand-listed samples.
 *
 * ### The combination scheme
 *
 * A full cartesian product over every optional field would be 2^12 shapes for a Task alone. This is
 * deliberately not that. The product is taken at the granularity of a family:
 *
 *  - **Within a [Family][com.circuitstitch.deferno.core.model.plugin.Plugin], full product.** The
 *    fields that map into one family interact — `completeBy` + `deadlineTimeOfDay` become one
 *    `Anchor`, `workingState` + `finishedAt` become one progress record — so every combination of
 *    them is generated.
 *  - **Across families, three baselines.** Each family's product runs on top of a *minimal* row
 *    (everything absent), a *saturated* row (every other field at a non-default value) and a
 *    *tombstoned* row. The saturated baseline is the one with teeth: it fails when writing one
 *    family back to the wire clobbers a field belonging to another.
 *
 * A three-way interaction between fields in three different families is not enumerated. The axes
 * are meant to be independent, so such an interaction is a defect in the cut rather than a case to
 * generate around — and it surfaces here as a saturated-baseline failure anyway.
 *
 * ### A fifth kind cannot be added without being covered
 *
 * [shapesOf] is an exhaustive `when` over [ItemKind]. A new kind is a **compile error** here, not a
 * silently smaller corpus. The counts in `KindRecipeRoundTripTest` guard the other direction: a
 * corpus that quietly shrinks fails rather than passing faster.
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
 * One shape in the corpus: a [label] naming how it was built, and the [KindRow] itself.
 *
 * The row is typed rather than an `Any`, and the four-arm dispatch over it lives once in [KindRecipe]
 * — so a harness reads and writes a shape without restating which kind it is holding.
 */
internal data class KindShape(val label: String, val row: KindRow) {

    val kind: ItemKind get() = row.kind

    /** This shape read into plugins. */
    fun read(recipe: KindRecipe = ParityRecipe): Item = recipe.read(row)

    /** [item] written back as a row of this shape's kind. */
    fun write(item: Item, recipe: KindRecipe = ParityRecipe): KindRow = recipe.write(item, kind)
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

// ── Declaring one kind's shapes ────────────────────────────────────────────────────────────────

@DslMarker
internal annotation class ShapeDsl

/** Builds one kind's corpus: [combinations] over the baselines and groups [build] declares. */
internal fun <T> shapes(seed: T, build: ShapeSpec<T>.() -> Unit): List<Pair<String, T>> =
    ShapeSpec<T>().apply(build).over(seed)

/** One kind's baselines and families, in declaration order. */
@ShapeDsl
internal class ShapeSpec<T> {

    private val baselines = mutableListOf<Choice<T>>()
    private val groups = mutableListOf<Group<T>>()

    /** A whole-row starting point that every family's product is applied on top of. */
    fun baseline(name: String, apply: (T) -> T) {
        baselines += Choice(name, apply)
    }

    /** One family's interacting fields. */
    fun group(family: String, build: GroupSpec<T>.() -> Unit) {
        groups += Group(family, GroupSpec<T>().apply(build).axes.toList())
    }

    fun over(seed: T): List<Pair<String, T>> = combinations(baselines, seed, groups)
}

/** One family's axes, in declaration order. */
@ShapeDsl
internal class GroupSpec<T> {

    val axes = mutableListOf<Axis<T>>()

    fun axis(build: AxisSpec<T>.() -> Unit) {
        axes += AxisSpec<T>().apply(build).choices.toList()
    }
}

/** One axis's named choices, in declaration order. */
@ShapeDsl
internal class AxisSpec<T> {

    val choices = mutableListOf<Choice<T>>()

    fun choice(name: String, apply: (T) -> T) {
        choices += Choice(name, apply)
    }
}

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

// ── Axes more than one kind declares ───────────────────────────────────────────────────────────
//
// The four kind types share no supertype, deliberately — see `Item`'s KDoc. So an axis cannot be
// shared as a value. It is shared as a declaration instead: the choices and their names live here
// once, and the caller supplies the one thing that differs, a setter for the kind at hand. That is
// what keeps a choice name byte-identical across the kinds that carry it.

/** `no-desc` / `desc`. */
internal fun <T> GroupSpec<T>.descriptionAxis(text: String, set: (T, String?) -> T) = axis {
    choice("no-desc") { set(it, null) }
    choice("desc") { set(it, text) }
}

/** `no-labels` / `labels`. */
internal fun <T> GroupSpec<T>.labelsAxis(labels: List<String>, set: (T, List<String>) -> T) = axis {
    choice("no-labels") { set(it, emptyList()) }
    choice("labels") { set(it, labels) }
}

/** `normal` / `fire-pinned` / `backlog`. Pinning rides with priority: both land in the same family. */
internal fun <T> GroupSpec<T>.priorityAxis(set: (T, Priority, Boolean) -> T) = axis {
    choice("normal") { set(it, Priority.Normal, false) }
    choice("fire-pinned") { set(it, Priority.Fire, true) }
    choice("backlog") { set(it, Priority.Backlog, false) }
}

/**
 * The three anchor shapes a deadline-bearing kind can be in: `unanchored` / `all-day-deadline` /
 * `timed-deadline`.
 *
 * A time without a day is not generated. The day comes from `completeBy`, so a `deadlineTimeOfDay`
 * on its own names no instant.
 */
internal fun <T> GroupSpec<T>.deadlineAxis(set: (T, Instant?, LocalTime?) -> T) = axis {
    choice("unanchored") { set(it, null, null) }
    choice("all-day-deadline") { set(it, deadline, null) }
    choice("timed-deadline") { set(it, deadline, fivePm) }
}

/** `no-target` / `target`. */
internal fun <T> GroupSpec<T>.targetAxis(set: (T, Instant?) -> T) = axis {
    choice("no-target") { set(it, null) }
    choice("target") { set(it, target) }
}

/** `no-rule` / `daily` / `every-2-until`. */
internal fun <T> GroupSpec<T>.recurrenceAxis(set: (T, Recurrence?) -> T) = axis {
    choice("no-rule") { set(it, null) }
    choice("daily") { set(it, everyDay) }
    choice("every-2-until") { set(it, everyOtherDayUntil) }
}

/**
 * `elided-series` / `series-inputs`.
 *
 * A null `series` is the wire's ELISION — "this device cannot reproduce that grid" — never an empty
 * grid. Absent and present are two different claims, so both are in the corpus.
 */
internal fun <T> GroupSpec<T>.seriesAxis(seriesId: String, set: (T, String?, SeriesInputs?) -> T) = axis {
    choice("elided-series") { set(it, null, null) }
    choice("series-inputs") { set(it, seriesId, seriesInputs) }
}

/** One choice per [DefinitionState], named after the state. */
internal fun <T> GroupSpec<T>.definitionStateAxis(set: (T, DefinitionState) -> T) = axis {
    DefinitionState.entries.forEach { state -> choice(state.name) { set(it, state) } }
}

/**
 * `unblocked` / `blocked` / `is-blocker`.
 *
 * The refs travel with the flag — a blocked row names what blocks it. A kind whose wire carries no
 * refs ignores that argument.
 */
internal fun <T> GroupSpec<T>.blockedAxis(set: (T, Boolean, Boolean, List<BlockedByRef>) -> T) = axis {
    choice("unblocked") { set(it, false, false, emptyList()) }
    choice("blocked") { set(it, true, false, blockedByOne) }
    choice("is-blocker") { set(it, false, true, emptyList()) }
}

// ── Task ───────────────────────────────────────────────────────────────────────────────────────

private object TaskShapes {

    private val seed = Task(
        id = TaskId("11111111-0000-4000-8000-000000000001"),
        orgSlug = "u-e4h2qk",
        title = "Renew the passport",
        workingState = WorkingState.Open,
        dateCreated = created,
    )

    fun all(): List<KindShape> = shapes(seed) {
        baseline("minimal") { it }
        baseline("saturated") {
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
        }
        baseline("tombstoned") { it.copy(deletedAt = deleted) }

        group("content") {
            descriptionAxis("at the passport office") { row, text -> row.copy(description = text) }
            labelsAxis(listOf("admin", "travel")) { row, labels -> row.copy(labels = labels) }
            priorityAxis { row, priority, pinned -> row.copy(priority = priority, pinned = pinned) }
            axis {
                choice("no-attachments") { it.copy(attachmentCount = 0, attachmentTotalSize = 0) }
                choice("attachments") { it.copy(attachmentCount = 2, attachmentTotalSize = 4096) }
            }
        }
        group("temporal") {
            deadlineAxis { row, at, time -> row.copy(completeBy = at, deadlineTimeOfDay = time) }
            targetAxis { row, at -> row.copy(targetDate = at) }
        }
        group("enactment") {
            // `finishedAt` is producted against every state on purpose: the wire can carry a finish
            // timestamp on a row that is not Done, and the round trip has to survive it rather than
            // tidy it away.
            axis { WorkingState.entries.forEach { state -> choice(state.name) { it.copy(workingState = state) } } }
            axis {
                choice("unfinished") { it.copy(finishedAt = null) }
                choice("finished") { it.copy(finishedAt = finished) }
            }
            axis {
                choice("no-productive") { it.copy(productive = null) }
                choice("productive") { it.copy(productive = 0.75) }
            }
        }
        group("modal") {
            // `desire` is a continuous Double? on this client and flows through the backup mappers,
            // so the corpus carries the values that bucketing to three would destroy — 0.0 is a
            // claim, and it is not `null`.
            axis {
                choice("no-desire") { it.copy(desire = null) }
                choice("desire-0") { it.copy(desire = 0.0) }
                choice("desire-mid") { it.copy(desire = 0.5) }
                choice("desire-1") { it.copy(desire = 1.0) }
            }
        }
        group("linkage") {
            blockedAxis { row, blocked, isBlocker, blockedBy ->
                row.copy(blocked = blocked, isBlocker = isBlocker, blockedBy = blockedBy)
            }
            axis {
                choice("native") { it.copy(external = null) }
                choice("imported") { it.copy(external = github) }
            }
            axis {
                choice("no-successor") { it.copy(nextTaskId = null) }
                choice("successor") { it.copy(nextTaskId = TaskId("11111111-0000-4000-8000-000000000003")) }
            }
        }
    }.map { (label, task) -> KindShape("Task/$label", KindRow.OfTask(task)) }
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

    fun all(): List<KindShape> = shapes(seed) {
        baseline("minimal") { it }
        baseline("saturated") {
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
        }
        baseline("tombstoned") { it.copy(deletedAt = deleted) }

        group("content") {
            descriptionAxis("twenty minutes") { row, text -> row.copy(description = text) }
            labelsAxis(listOf("music")) { row, labels -> row.copy(labels = labels) }
            priorityAxis { row, priority, pinned -> row.copy(priority = priority, pinned = pinned) }
        }
        group("temporal") {
            deadlineAxis { row, at, time -> row.copy(completeBy = at, deadlineTimeOfDay = time) }
            targetAxis { row, at -> row.copy(targetDate = at) }
        }
        group("unfolding") {
            recurrenceAxis { row, rule -> row.copy(recurrence = rule) }
            seriesAxis("series-h1") { row, id, inputs -> row.copy(seriesId = id, series = inputs) }
            definitionStateAxis { row, state -> row.copy(definitionState = state) }
        }
        group("linkage") {
            blockedAxis { row, blocked, isBlocker, _ -> row.copy(blocked = blocked, isBlocker = isBlocker) }
        }
    }.map { (label, habit) -> KindShape("Habit/$label", KindRow.OfHabit(habit)) }
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

    fun all(): List<KindShape> = shapes(seed) {
        baseline("minimal") { it }
        baseline("saturated") {
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
        }
        baseline("tombstoned") { it.copy(deletedAt = deleted) }

        group("content") {
            descriptionAxis("green bin on Tuesdays") { row, text -> row.copy(description = text) }
            labelsAxis(listOf("house")) { row, labels -> row.copy(labels = labels) }
            priorityAxis { row, priority, pinned -> row.copy(priority = priority, pinned = pinned) }
        }
        group("temporal") {
            deadlineAxis { row, at, time -> row.copy(completeBy = at, deadlineTimeOfDay = time) }
            targetAxis { row, at -> row.copy(targetDate = at) }
        }
        group("unfolding") {
            recurrenceAxis { row, rule -> row.copy(recurrence = rule) }
            // `cadenceMode` is Chore-only and NON-NULL: an absent wire token is not "unknown", it IS
            // Rolling. Both real modes plus the unmodelled escape hatch are swept.
            axis {
                choice("rolling") { it.copy(cadenceMode = CadenceMode.Rolling) }
                choice("fixed") { it.copy(cadenceMode = CadenceMode.Fixed) }
                choice("unmodelled-mode") { it.copy(cadenceMode = CadenceMode.Unmodelled("drifting")) }
            }
            seriesAxis("series-c1") { row, id, inputs -> row.copy(seriesId = id, series = inputs) }
            definitionStateAxis { row, state -> row.copy(definitionState = state) }
        }
        group("linkage") {
            blockedAxis { row, blocked, isBlocker, _ -> row.copy(blocked = blocked, isBlocker = isBlocker) }
        }
    }.map { (label, chore) -> KindShape("Chore/$label", KindRow.OfChore(chore)) }
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

    fun all(): List<KindShape> = shapes(seed) {
        baseline("minimal") { it }
        baseline("saturated") {
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
        }
        baseline("tombstoned") { it.copy(deletedAt = deleted) }

        group("content") {
            descriptionAxis("the daily one") { row, text -> row.copy(description = text) }
            labelsAxis(listOf("work")) { row, labels -> row.copy(labels = labels) }
            priorityAxis { row, priority, pinned -> row.copy(priority = priority, pinned = pinned) }
        }
        group("temporal") {
            // THE conflation this migration exists to unsmear, generated faithfully rather than
            // corrected. An Event's `completeBy` is a START, the same field name three other kinds
            // use for a DEADLINE. `allDay` is server-derived from the two time-of-day fields being
            // null, so the corpus carries `allDay` disagreeing with them too — the wire can.
            axis {
                choice("no-window") { it.copy(completeBy = null, endTime = null) }
                choice("start-only") { it.copy(completeBy = deadline, endTime = null) }
                choice("start-and-end") { it.copy(completeBy = deadline, endTime = endsAt) }
            }
            axis {
                choice("all-day-times") { it.copy(startTimeOfDay = null, endTimeOfDay = null) }
                choice("start-time") { it.copy(startTimeOfDay = fivePm, endTimeOfDay = null) }
                choice("both-times") { it.copy(startTimeOfDay = fivePm, endTimeOfDay = sixThirty) }
            }
            axis {
                choice("all-day-false") { it.copy(allDay = false) }
                choice("all-day-true") { it.copy(allDay = true) }
            }
            targetAxis { row, at -> row.copy(targetDate = at) }
        }
        group("unfolding") {
            recurrenceAxis { row, rule -> row.copy(recurrence = rule) }
            seriesAxis("series-e1") { row, id, inputs -> row.copy(seriesId = id, series = inputs) }
            definitionStateAxis { row, state -> row.copy(definitionState = state) }
        }
        group("linkage") {
            blockedAxis { row, blocked, isBlocker, _ -> row.copy(blocked = blocked, isBlocker = isBlocker) }
        }
    }.map { (label, event) -> KindShape("Event/$label", KindRow.OfEvent(event)) }
}

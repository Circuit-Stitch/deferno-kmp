package com.circuitstitch.deferno.core.model

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.time.Instant

/**
 * The kind-neutral read model of one recurring definition (#383) — the shared shape of [Habit], [Chore]
 * and [Event], so a single detail surface serves all three without a `when (kind)` in the View.
 *
 * **This is a projection, not a supertype.** The three domain types stay unrelated, exactly as [Item]'s
 * KDoc records: they are four wire shapes with genuinely different payloads, and giving them a common
 * ancestor would force every kind-specific field through it. This carries only what a *read-only*
 * definition detail renders, which is why a [Chore]'s `cadenceMode` and an [Event]'s `endTime` are
 * absent — the detail does not show them yet, and a projection that carries fields nobody reads is a
 * second model to keep in step for no benefit.
 *
 * **It deliberately overlaps [Item] rather than replacing it.** [Item] is the *tree row* — small, spans
 * all four kinds, and is what the display readings ([recurrenceReading], [recurrenceCursor]) are already
 * written against. It carries no `description` and no `labels`, which are two of the fields a detail
 * must show, so the detail needs the concrete cached row as well. [toItem] bridges back so the readings
 * are reused rather than reimplemented — the fifth copy of cadence normalisation is the thing #384 went
 * out of its way to prevent.
 *
 * [cursorAt] is the wire's `complete_by` and is the [[Recurrence cursor]] — where the series has *walked
 * to*, never an upper bound. It is named for what it is, following [Item.recurrenceCursorAt] and for the
 * same reason: on a recurring definition this field moves on every resolution, and reading it as a
 * deadline is the mis-read the whole recurring epic keeps tripping over. The bound lives on
 * [recurrence] as a [RecurrenceBound].
 */
@OptIn(ExperimentalObjCName::class)
data class RecurringDefinition(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val definitionState: DefinitionState,
    // Named explicitly for the Apple export: `description` collides with `-[NSObject description]` and
    // would otherwise land in Swift as the unstable `description_` — the same treatment the three
    // concrete models give it.
    @property:ObjCName("itemDescription") val description: String? = null,
    val labels: List<String> = emptyList(),
    val recurrence: Recurrence? = null,
    /** Wire `complete_by` — the walked CURSOR, never a bound. See the class KDoc. */
    val cursorAt: Instant? = null,
    val seriesId: String? = null,
    /**
     * The offline expansion inputs (#410, ADR-0053/0054). `null` is the backend's deliberate **elision**
     * — "this device cannot reproduce that grid" — and never a grid with no exclusions; see
     * [SeriesInputs]. A caller must not read it as "nothing is scheduled".
     */
    val series: SeriesInputs? = null,
    val parentId: TaskId? = null,
    val ref: String? = null,
    val hydration: HydrationState = HydrationState.Summary,
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
) {
    /** This definition's address, kind intact. */
    val itemRef: ItemRef get() = ItemRef(id, kind)
}

/** The kind-neutral projection of this Habit. */
fun Habit.toDefinition(): RecurringDefinition = RecurringDefinition(
    id = id.value,
    kind = ItemKind.Habit,
    title = title,
    definitionState = definitionState,
    description = description,
    labels = labels,
    recurrence = recurrence,
    cursorAt = completeBy,
    seriesId = seriesId,
    series = series,
    parentId = parentId,
    ref = ref,
    hydration = hydration,
    blocked = blocked,
    isBlocker = isBlocker,
)

/** The kind-neutral projection of this Chore. */
fun Chore.toDefinition(): RecurringDefinition = RecurringDefinition(
    id = id.value,
    kind = ItemKind.Chore,
    title = title,
    definitionState = definitionState,
    description = description,
    labels = labels,
    recurrence = recurrence,
    cursorAt = completeBy,
    seriesId = seriesId,
    series = series,
    parentId = parentId,
    ref = ref,
    hydration = hydration,
    blocked = blocked,
    isBlocker = isBlocker,
)

/** The kind-neutral projection of this Event. */
fun Event.toDefinition(): RecurringDefinition = RecurringDefinition(
    id = id.value,
    kind = ItemKind.Event,
    title = title,
    definitionState = definitionState,
    description = description,
    labels = labels,
    recurrence = recurrence,
    cursorAt = completeBy,
    seriesId = seriesId,
    series = series,
    parentId = parentId,
    ref = ref,
    hydration = hydration,
    blocked = blocked,
    isBlocker = isBlocker,
)

/**
 * The [Item] projection of this definition — the input [Item.recurrenceReading] and
 * [Item.recurrenceCursor] are written against, so the detail phrases cadence and next-due through the
 * one shared reading instead of growing a parallel copy (#384's whole point).
 *
 * `isTerminal` follows the tree's own rule for a recurring row: an Archived definition is the
 * de-emphasized one. The tree-only fields it cannot know — the subtree counts, the dependency edge list,
 * the external provenance — stay at their defaults; nothing on the detail reads them.
 */
fun RecurringDefinition.toItem(): Item = Item(
    id = id,
    kind = kind,
    title = title,
    parentId = parentId?.value,
    isTerminal = definitionState == DefinitionState.Archived,
    blocked = blocked,
    isBlocker = isBlocker,
    definitionState = definitionState,
    recurrence = recurrence,
    recurrenceCursorAt = cursorAt,
    seriesId = seriesId,
    series = series,
)

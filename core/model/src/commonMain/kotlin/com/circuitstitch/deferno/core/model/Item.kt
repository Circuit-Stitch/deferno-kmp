package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * The common projection across the four [ItemKind]s (CONTEXT.md → "Item") — the cross-kind read model
 * the Tasks [Item tree] (ADR-0049, #226/#227) renders as one forest. [Task]/[Habit]/[Chore]/[Event]
 * are four unrelated domain types with **no supertype**; this is the small shared shape a tree row
 * needs, built from each kind's cached row by the `Item` mappers in `core:data`. Per-kind detail still
 * loads the concrete model on open — this carries only structure plus the fields a collapsed or
 * de-emphasized row shows, not the kind-specific payload.
 *
 * **Ids are raw UUID strings, not kind-typed.** The tree spans kinds, so a child may nest under a
 * parent of *any* kind ([parentId] matches some other row's [id] regardless of kind), and the
 * device-local fold store keys by this id. (The per-kind models wrap the same wire UUID in their own
 * id type; this projection unwraps it to the string the forest + fold store compare on.)
 *
 * [isTerminal] is the de-emphasis signal — a Done/Dropped [Task] ([WorkingState.isTerminal]) or an
 * Archived recurring definition. [definitionState] is the recurring-kind "light switch" carried through
 * so the tree's command menu can set it (#299): `null` for a [Task] (its lifecycle is [WorkingState], not
 * a definition state), populated for a Habit/Chore/Event. [descendantDone]/[descendantTotal] are the server-computed subtree
 * counts for a collapsed node's progress badge; the `/items` snapshot computes them on Tasks only, so
 * they are `null` for the recurring kinds.
 *
 * [source] is the item's external provenance for a small row indicator: `null` = a native Deferno item
 * (the common case), else the external system it was synced/created from. The tree row renders the
 * matching brand mark when non-null. [externalRef] is the opaque provider id (`owner/repo#N`) that the
 * row's dimmed `[GitHub#N]` ref prefix is derived from — carried alongside [source] rather than the full
 * [ExternalRef] because the row only shows the mark + the ref number (the detail Source cell owns the URL
 * link), keeping this projection minimal.
 *
 * [blocked]/[isBlocker] are **server-derived** dependency flags (ADR-0034, #289), treated as read-only
 * truth — the client never re-derives the readiness rules. [blocked] is `true` when this item has an
 * unresolved blocker *or* an ancestor is blocked (the flag inherits down the tree); [isBlocker] is
 * `true` when this item currently gates at least one other (a reverse-index flag). Both default `false`
 * so a payload omitting them decodes cleanly. [blockedBy] is the ordered edge list itself (#291):
 * populated for a Task (edges are Task-held; the `/items` snapshot now ships them per row), always
 * empty for the recurring kinds. The tree's "Blocked by…" picker and the destructive-unblock
 * confirmation's dependents scan read it; readiness itself still comes from the server flags.
 *
 * [recurrence]/[recurrenceCursorAt] are the recurring pair (#384) — the rule and its **cursor**. They
 * are carried together, and neither is redundant: read as a pair they are the only way to tell a live
 * series from a finished one. On a recurring definition `complete_by` is a *moving cursor* that
 * advances as occurrences are resolved, **not** an upper bound (the backend's
 * `2026-06-02-recurrence-anchor-and-bound` ADR) — the bound lives on the rule as [RecurrenceBound].
 * So a cursor in the past is the normal reading for a missed Habit, and a rule whose cursor has been
 * *cleared* means the series ran out. [RecurrenceCursor] is that reading; it is derived at render
 * time, never stored here.
 *
 * **[recurrenceCursorAt] is deliberately not called `completeBy`**, though that is the wire/domain
 * field it comes from. [Task], [Habit], [Chore] and [Event] each carry a `completeBy` meaning "the
 * hard deadline", and on this cross-kind projection the same name would mean *cursor* for three kinds
 * and *nothing at all* for the fourth — a Task's deadline is deliberately **not** projected here,
 * because conflating the two would make every dated Task read as an exhausted-or-due series. A future
 * row decoration that wants the deadline must project it as its own field; the name says so.
 *
 * [seriesId]/[series] complete that pair (#410, ADR-0053). The rule says *how often*; the cursor says
 * *how far along*; only the inputs say **which wall times** — the frozen anchor, the zone it was frozen
 * in, the [[Segment]] bound and the per-instance exceptions. With all three a tree row can hand
 * [expandOccurrenceGrid] everything it needs and get real firing dates back **cold**, off the cached
 * snapshot, with no detail fetch and no network. That is a deliberate choice and not an incidental one:
 * the wire carries `series` on every `/items` row, not merely on `/items/{id}`, so projecting it here
 * costs one cached column set and saves a per-row round trip the Plan (#385) would otherwise need.
 * Inheriting the detail-only assumption instead would have left the tree and the Plan unable to reach
 * a grid at all — the gap #410 was opened to close.
 *
 * **[series] is `null` in two very different situations, and a caller must not conflate them.** For a
 * [Task] it means *nothing at all* (a Task has no series). For a recurring kind it is the backend's
 * **elision** — "no series row backs this item, this device cannot reproduce that grid" — which is not
 * the same as a grid with no exclusions. [SeriesInputs] carries the full reasoning.
 */
data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val parentId: String? = null,
    val sequence: Long? = null,
    val isTerminal: Boolean = false,
    val descendantDone: Long? = null,
    val descendantTotal: Long? = null,
    val source: ItemSource? = null,
    val externalRef: String? = null,
    val blocked: Boolean = false,
    val isBlocker: Boolean = false,
    val blockedBy: List<BlockedByRef> = emptyList(),
    // The recurring-kind "light switch" (#299): `null` for a Task (its lifecycle is [WorkingState]),
    // populated for a Habit/Chore/Event so the Item-tree command menu can set it (Archive / Restore).
    // [isTerminal] is still kept (an Archived definition is de-emphasized) — this carries the full state.
    val definitionState: DefinitionState? = null,
    // The recurrence rule (#384), so a row can say "every Tuesday" without loading the concrete kind.
    // `null` for a Task, and for a recurring definition whose rule did not survive the wire.
    val recurrence: Recurrence? = null,
    // The recurrence CURSOR — where the series has walked to, NOT an upper bound (see the class KDoc).
    // `null` alongside a [recurrence] means the series is exhausted; `null` for a Task means nothing at
    // all (a Task's deadline is not a cursor, and is not projected here). Read the pair through
    // [recurrenceCursor] — never this field raw.
    val recurrenceCursorAt: Instant? = null,
    // The series this row's firings belong to (#410). Names the series; is NEVER a path key (#380) —
    // every kind-scoped route keys on the DEFINITION id, which is [id].
    val seriesId: String? = null,
    // The offline expansion inputs (#410) — see the class KDoc. Present on the `/items` snapshot, so a
    // tree/Plan row reaches a real grid cold. `null` for a Task; for a recurring kind, the elision.
    val series: SeriesInputs? = null,
)

/** An item's external provenance — the system it was synced/created from (drives the row's source mark). */
enum class ItemSource { GitHub, GoogleCalendar }

/**
 * The full external provenance carried on the single-item record ([Task.external]): the [source], the
 * opaque provider [id] (`owner/repo#N` for a GitHub-imported issue), and the optional provider-side [url].
 * It drives three surfaces: the source mark, the dimmed `[GitHub#N]` ref prefix (derived from [id]), and
 * the detail Source cell (which links to [url]).
 *
 * The detail's "origin label" is derived client-side from [id] — for a tracker ref it *is* the id — so no
 * separate server field is needed. A non-tracker source (e.g. a calendar id with no issue number) falls
 * back to its provider label until display-name resolution lands.
 */
data class ExternalRef(
    val source: ItemSource,
    val id: String,
    val url: String? = null,
)

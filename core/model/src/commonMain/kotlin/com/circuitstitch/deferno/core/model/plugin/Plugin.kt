package com.circuitstitch.deferno.core.model.plugin

import kotlin.reflect.KClass

/**
 * Which record owns a plugin — **a field, not a type** (ADR-0055).
 *
 * Two values because there are two records: the [Item] definition, and one dated [Occurrence] of it.
 *
 * The reason this is a field is the whole placement argument. Encoding "lives on an Occurrence" as a
 * *supertype* makes the placement check a `when` over overlapping plugin types, which the compiler
 * accepts as exhaustive while its correctness depends on branch order — and which stays exhaustive
 * when a branch is deleted. As a field the check becomes [misplaced], a filter, and a plugin that
 * forgets to answer does not compile at all (there is no default).
 */
enum class Scope {
    /** The [Item] owns it. One value covers the thing however many times it happens. */
    Definition,

    /** One dated [Occurrence] owns it. The value exists only *after* the engagement. */
    Occurrence,
}

/**
 * How far a plugin's value can travel — **a field, not a type**, for the same reason [Scope] is
 * (ADR-0057).
 *
 * The plugin model is strictly more expressive than the four kinds the wire still speaks. Five
 * Family members fall outside what the wire can carry at all: the bound that says whether a thing
 * has an endpoint, the verdict on whether a goal obtained, the purpose edges an item points at,
 * deontic obligation, and persistence policy. Withholding all five until the backend's port lands
 * would leave the migration unable to demonstrate the point of itself, so they are held on the
 * device instead.
 *
 * The boundary is mechanical and runs along Family *members*: a member is wire-backed or it is not,
 * and nothing is both. That is what makes this a field a reader can filter on rather than a
 * judgement a caller has to make — the outbox never sees a [DeviceLocal] plugin, a refresh never
 * clears one, and a surface a person can act on marks one as not synced.
 */
enum class Reach {
    /**
     * The four-kind wire has a field for this, so it round-trips and reaches the server. The
     * representable set is pinned to exactly this (ADR-0056), which is why nothing a person creates
     * offline can fail to sync.
     */
    Wire,

    /**
     * No wire field exists. The value lives in the device-local shadow store, never reaches the
     * outbox, and is **expected to be lost** at the cutover — a deliberate pre-launch trade, not an
     * oversight, and the reason it is surfaced as unsynced wherever a person can act on it.
     */
    DeviceLocal,
}

/**
 * One slice of an [Item]'s data plus the behaviour over it: the data class **is** the plugin,
 * presence in the list **is** the composition, and the set is **closed** (ADR-0055).
 *
 * ### The eight families
 *
 * The sub-interfaces below the divider are the eight meaning families — one per thing a person needs
 * to be able to say about an item, and therefore one conversion axis each:
 *
 * | family         | what it says                    | wire status (ADR-0056/0057)                 |
 * |----------------|---------------------------------|---------------------------------------------|
 * | [Content]      | what it is                      | backed                                      |
 * | [Unfolding]    | how it unfolds over time        | split — `Repeats` backed, the bound shadowed |
 * | [Temporal]     | when, relative to what          | backed                                      |
 * | [Modal]        | how strongly — must vs want     | split — volition backed, obligation shadowed |
 * | [Participant]  | who                             | never modelled client-side                  |
 * | [Enactment]    | what actually happened          | split — the verdict has no wire home        |
 * | [Persistence]  | what if it is *not* done        | shadowed                                    |
 * | [Linkage]      | how it relates to other items   | split — purpose shadowed                    |
 *
 * Two shapes are losslessly inter-convertible **iff they differ only in which member of one family
 * is loaded**. That is the property the re-cut exists for: today's four kinds are effectively one
 * family, so every conversion between them is lossy.
 *
 * The families are **documentation in types and nothing more** — none of the eight is ever matched.
 * `sealed` on them fixes where a ninth may be *declared* (same module) and says nothing about how
 * many there are or whether they overlap. [Scope] is the deliberate contrast: a third value there is
 * a compile error, because something reads it.
 *
 * ### The seal has teeth
 *
 * `Plugin` is sealed so that the sites which must handle every member cannot silently skip a new one.
 * Today the only such site is the seal witness in `PluginSealTest`; from #418 the parity recipe's
 * **write** direction joins it, and that is the one that matters — a plugin the writer forgets is a
 * field silently dropped on the way back to the wire.
 *
 * ### Do not add a `when` over plugin types
 *
 * Nothing in this package branches on which plugin it is holding. Placement is [misplaced] (a filter
 * over [scope]); exclusivity is [exclusivityProblems] (a grouping over [family]); a reader asks for
 * the type it wants through [PluginHost.plugin]. Reintroducing a type cascade is how ADR-0055's
 * predecessor experiment shipped a placement check that type-checked while being wrong.
 */
sealed interface Plugin {

    /** Which record owns this plugin. Deliberately abstract — see [Scope]. */
    val scope: Scope

    /**
     * How far this plugin's value can travel — see [Reach] (ADR-0057).
     *
     * Abstract, like [scope] and [degenerate], and for the sharpest of the three reasons: neither
     * default is safe. Defaulting to [Reach.Wire] would let a Family the server has no field for be
     * enqueued as a mutation that can never drain; defaulting to [Reach.DeviceLocal] would silently
     * stop sending a Family that has a field. So a new Family answers, or it does not compile.
     */
    val reach: Reach

    /**
     * The exclusive family this plugin belongs to. At most one member of a family may be loaded;
     * [exclusivityProblems] enforces it.
     *
     * Defaults to the plugin's own type, because a second [Prioritizable] is not a composition — it
     * is two answers to one question, and every reader of a sparse list takes the first. A plugin
     * that genuinely holds several of something holds a **list** instead.
     *
     * A family whose members sit under a sealed parent overrides this to name that parent, so that
     * two members of the same pick-one are caught as the duplicate answer they are.
     *
     * This is **not** the meaning family above. [Unfolding] is one meaning whose bound is exclusive
     * and whose recurrence composes freely with it: one meaning, two rules.
     */
    val family: KClass<out Plugin> get() = this::class

    /**
     * What this plugin's family reads as when **nothing** is loaded — the value the non-generic
     * accessor on [PluginHost] returns for an absent plugin.
     *
     * Abstract with no default, so a new Family cannot land without answering *"what does silence
     * mean here?"*. That is the second half of the seal's teeth and the one that matters for
     * translation: a recipe decides whether to load a plugin by asking whether it differs from this,
     * so a Family that got it wrong would either load nothing it should or load everything it should
     * not — both of which the round-trip gate sees.
     *
     * A family whose members sit under a sealed parent answers **once, on the parent**, naming the
     * member that means silence. That is why the answer is a value rather than a type: `Unanchored`
     * is a real member, not the absence of one, and every other member is measured against it.
     *
     * ### Not a `when` over plugin types, deliberately
     *
     * The obvious alternative is one exhaustive `when (plugin)` in the recipe layer mapping each
     * type to its degenerate value. It would compile and it would have the same teeth. It would also
     * be a type cascade over the plugin hierarchy sitting in production code — the exact shape
     * ADR-0055 rejects for placement — and the next such `when` would have precedent. Asking the
     * plugin keeps the answer beside the data that defines it.
     */
    val degenerate: Plugin

    /**
     * Whether this plugin claims anything beyond its family's silence.
     *
     * The sparseness rule in one line: a recipe loads a plugin only when this is `true`, because a
     * plugin equal to its [degenerate] value is already what an absent one reads as. Loading it
     * anyway would give one row two plugin lists that mean the same thing, and the round trip would
     * be equivalence rather than identity.
     */
    val saysSomething: Boolean get() = this != degenerate

    /** Reasons this plugin's own data is invalid. Empty when valid. */
    fun validate(): List<String> = emptyList()
}

// ── The eight meaning families ─────────────────────────────────────────────────────────────────

/** What the thing *is*: its predicate, its labels, the stuff hung on it. */
sealed interface Content : Plugin

/** How the thing unfolds over time — the family an aspect reading is derived from. */
sealed interface Unfolding : Plugin

/** When it is committed to happen, and relative to what. */
sealed interface Temporal : Plugin

/** Must versus want — deontic and volitive modality. */
sealed interface Modal : Plugin

/** Who is expected to do it. Never modelled client-side; the marker exists so the axis is named. */
sealed interface Participant : Plugin

/** What actually happened on one date. Records, never definitions. */
sealed interface Enactment : Plugin

/** What becomes of it if it is *not* done. */
sealed interface Persistence : Plugin

/** How it links to other Items. */
sealed interface Linkage : Plugin

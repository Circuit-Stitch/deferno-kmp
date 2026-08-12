package com.circuitstitch.deferno.core.model.plugin

import kotlin.reflect.KClass

/**
 * Something that carries a sparse list of [Plugin]s. Both records do — the [Item] definition and one
 * dated [Occurrence] of it — so every reader below is written once.
 *
 * ## The accessor convention — read this before adding a Family
 *
 * Every Family gets a **non-generic, total** accessor here, returning a non-null value: an absent
 * plugin reads as that Family's degenerate value, so no caller ever handles "absent". [priority] is
 * the worked example.
 *
 * Two independent reasons, and both have to hold:
 *
 *  1. **Totality.** "Absent" is not a state the UI, the recipes or a derived reading should branch
 *     on. The degenerate value carries the meaning — no priority loaded *is* `Normal`, unpinned.
 *     Where absence genuinely means something *different* from the degenerate value (the bound under
 *     [Unfolding] is underspecified, not defaulted), the Family says so in its own KDoc and its
 *     degenerate value is the **underspecified** member, never a guessed one.
 *
 *  2. **The Apple bridge.** [plugin] and [has] are `inline fun <reified T>`. An inline function has
 *     no callable symbol in the compiled binary, so it never reaches the Obj-C API surface — and
 *     SKIE sits on that surface, so it cannot recover them either. A generic-only reader is
 *     therefore **invisible to Swift**: `app/iosApp` and `app/macosApp` (27 Swift files, Phase 5)
 *     could not read a single plugin.
 *
 * ### Why the accessors are members here rather than extension properties
 *
 * The reference model in `DefernoPlugins` writes them as top-level extensions, which reads better in
 * Kotlin. It is the wrong choice on this side of the fence: `core:model` is exported into
 * `Deferno.framework`, an exported Kotlin interface becomes an Obj-C `@protocol`, and a member is
 * unambiguously a property on that protocol — `item.priority` in Swift. An extension on an interface
 * has no protocol to hang off and lands in a file-facade class at best. Coupling this interface to
 * every Family is the price, and it is cheap because the plugin set is closed anyway (ADR-0055): one
 * file to check that no Family arrived without a way for Swift to read it.
 *
 * `AppleFrameworkConfig` deliberately refuses a blanket name-collision suppression for this package —
 * *"that is the package the gate most needs to keep policing"* — so an accessor whose name collides
 * with an Obj-C selector gets an `@ObjCName` at the declaration, the way the four kinds' `description`
 * does. Prefer a name that does not collide.
 *
 * **Add the accessor in the same commit as the Family.** A Family without one is unreachable from
 * Swift, and the gap is invisible until Phase 5.
 */
interface PluginHost {

    val plugins: List<Plugin>

    // ── Content ────────────────────────────────────────────────────────────────────────────────

    /** Prose. Degenerate: no description — which on a summary row means *not hydrated*. */
    val describable: Describable get() = plugin<Describable>() ?: Describable()

    /** Tags. Degenerate: none. */
    val taggable: Taggable get() = plugin<Taggable>() ?: Taggable()

    /** The backend-hosted attachment rollup. Degenerate: no files, no bytes. */
    val attachable: Attachable get() = plugin<Attachable>() ?: Attachable()

    /**
     * This record's urgency bucket and pin flag — degenerate value: `Priority.Normal`, unpinned.
     *
     * The worked example the convention above is written from, and deliberately the dullest Family
     * member there is: [Prioritizable] wraps the shipped `Priority` enum, whose three values are
     * already exactly the reference model's, so nothing here is mapped and nothing here is a
     * decision.
     */
    val priority: Prioritizable get() = plugin<Prioritizable>() ?: Prioritizable()

    // ── Temporal ───────────────────────────────────────────────────────────────────────────────

    /** When this is committed to happen. Degenerate: [Anchor.Unanchored] — wanted, not scheduled. */
    val anchor: Anchor get() = plugin<Anchor>() ?: Anchor.Unanchored

    /** The soft "want done by" date. Degenerate: none. */
    val targeted: Targeted get() = plugin<Targeted>() ?: Targeted()

    // ── Unfolding ──────────────────────────────────────────────────────────────────────────────

    /**
     * The rule this happens on. Degenerate: no rule — and unlike the bound (#419) that is a
     * **determinate** answer, not an underspecified one.
     */
    val repeats: Repeats get() = plugin<Repeats>() ?: Repeats()

    // ── Enactment ──────────────────────────────────────────────────────────────────────────────

    /** Where this has got to, and when it stopped. Degenerate: [Lifecycle.Unstated], unfinished. */
    val progress: Progress get() = plugin<Progress>() ?: Progress()

    /** How the doing felt. Degenerate: unrecorded. */
    val trackable: Trackable get() = plugin<Trackable>() ?: Trackable()

    // ── Linkage ────────────────────────────────────────────────────────────────────────────────

    /** Readiness and the edges behind it. Degenerate: unblocked, gating nothing. */
    val blocker: Blocker get() = plugin<Blocker>() ?: Blocker()

    /** The forward hand-off edge. Degenerate: none. */
    val succeeds: Succeeds get() = plugin<Succeeds>() ?: Succeeds()

    /** Upstream provenance. Degenerate: a native Deferno item. */
    val importable: Importable get() = plugin<Importable>() ?: Importable()

    // ── Modal ──────────────────────────────────────────────────────────────────────────────────

    /**
     * How much the person wants to. Degenerate: `null` desire, which reads as
     * [Strength.Unstated] — the question was never put, which is not the same as "no".
     */
    val volition: Volition get() = plugin<Volition>() ?: Volition()
}

/**
 * The loaded member of type [T], or `null` when the list is silent about it.
 *
 * An **extension** function rather than a member, because Kotlin prohibits `inline` on virtual
 * members — and without `inline reified` a caller would have to pass a `KClass`, which is the
 * untyped-downcast ergonomics the closed set exists to avoid.
 *
 * **Not for ordinary callers.** They read a total accessor, so nothing outside this package handles
 * "absent". This is the primitive those accessors are written in terms of, and the escape hatch for
 * a reader that genuinely needs to tell absent from degenerate.
 */
inline fun <reified T : Plugin> PluginHost.plugin(): T? =
    plugins.filterIsInstance<T>().firstOrNull()

/** Whether a member of type [T] is loaded at all. See [plugin] for who may ask. */
inline fun <reified T : Plugin> PluginHost.has(): Boolean = plugin<T>() != null

/**
 * Replace whichever member of [replacement]'s exclusive family is loaded with [replacement], leaving
 * every other family untouched — **the conversion primitive** (ADR-0055).
 *
 * A conversion that used to be "this Chore becomes a Task" is this call: one family swaps, and
 * content, labels, modality and history never move, because they live in other families.
 *
 * The family is taken from [Plugin.family] rather than from the concrete type, so a swap *inside* a
 * sealed-parent family removes the sibling rather than sitting beside it — the case a
 * `filterNot { it is Deadline }` would get wrong.
 */
fun List<Plugin>.replacingFamilyOf(replacement: Plugin): List<Plugin> =
    filterNot { it.family == replacement.family } + replacement

/** Unload every member of [family], returning readers of it to that family's degenerate value. */
fun List<Plugin>.withoutFamily(family: KClass<out Plugin>): List<Plugin> =
    filterNot { it.family == family }

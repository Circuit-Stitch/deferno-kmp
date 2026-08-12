@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
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
 *     therefore **invisible to Swift**: the 95 hand-written Swift files across `app/iosApp` and
 *     `app/macosApp` (Phase 5) could not read a single plugin.
 *
 * ### Why the accessors are members here rather than extension properties
 *
 * **Not for reachability** — a top-level extension property on an interface reaches Swift perfectly
 * well through SKIE, which re-exposes it as an extension property on the generated protocol. The
 * proof is already in this module: `val Cadence.intervalAsDays` is a top-level extension on a sealed
 * *interface* and the generated framework surfaces it as `extension Cadence { var intervalAsDays }`.
 * Either form gives Swift `item.priority`.
 *
 * The reason is duller and is about this file rather than about Swift: a Family with no accessor is
 * unreachable from Swift, and the gap is invisible until Phase 5. As members they are all in one
 * place, so "did every Family get one?" is a question answered by scrolling rather than by grepping
 * the module. The coupling that costs — this interface naming every Family — is not a leak, because
 * the plugin set is closed and lives in this one package anyway (ADR-0055).
 *
 * `AppleFrameworkConfig` deliberately refuses a blanket name-collision suppression for this package —
 * *"that is the package the gate most needs to keep policing"* — so an accessor whose name collides
 * with an Obj-C selector gets an `@ObjCName` at the declaration, the way the four kinds' `description`
 * does. Prefer a name that does not collide.
 *
 * **Add the accessor in the same commit as the Family.** A Family without one is unreachable from
 * Swift, and the gap is invisible until Phase 5.
 */
@ObjCName("PluginHost")
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

    // ── The shadowed families (ADR-0057) ───────────────────────────────────────────────────────
    //
    // Accessors on the same terms as the wire-backed ones, deliberately: a reader should not have to
    // know which side of the wire boundary a Family sits on to read it. Which values cannot be sent
    // is answered once, by the clamp in `recipe/Clamp.kt`, rather than bolted onto every read.

    /**
     * Whether one doing of this has an endpoint, and what sort. Degenerate: [Dynamics.Unstated] —
     * **underspecified**, not defaulted. Nobody has said, which is not "it has none".
     */
    val dynamics: Dynamics get() = plugin<Dynamics>() ?: Dynamics.Unstated

    /** The verdict on the criterion for one date. Degenerate: nobody has evaluated it. */
    val evaluation: Evaluation get() = plugin<Evaluation>() ?: Evaluation()

    /** What this is for. Degenerate: nothing says. */
    val purpose: Purpose get() = plugin<Purpose>() ?: Purpose()

    /** How obligatory this is. Degenerate: the question was never put. */
    val obligation: Obligation get() = plugin<Obligation>() ?: Obligation()

    /**
     * What becomes of an unresolved occurrence at its horizon. Degenerate:
     * [PersistencePolicy.UntilComplete] — a **default**, and unlike [dynamics] a real claim.
     */
    val persistence: PersistencePolicy
        get() = plugin<PersistencePolicy>() ?: PersistencePolicy.UntilComplete
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

/**
 * Unload the whole family [member] belongs to, returning readers of it to its degenerate value.
 *
 * Takes a **member instance** rather than a `KClass`, for the same reason [replacingFamilyOf] keys
 * off [Plugin.family]: in a sealed-parent family the member's own class is *not* the family key, so
 * a `KClass`-taking version handed `Deadline::class` would match nothing, remove nothing, report
 * nothing, and leave the reader still returning the loaded value. Asking an instance means the key
 * comes from the same place the loaded plugins' keys do.
 */
fun List<Plugin>.withoutFamilyOf(member: Plugin): List<Plugin> =
    filterNot { it.family == member.family }

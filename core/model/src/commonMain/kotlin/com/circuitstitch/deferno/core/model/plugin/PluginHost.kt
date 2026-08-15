@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
/**
 * Something that carries a sparse list of [Plugin]s (ADR-0055). Both records do — the [Item]
 * definition and one dated [Occurrence] of it — so every reader below is written once.
 *
 * ## The accessor convention — read this before adding a Family
 *
 * Every Family gets a **non-generic, total** accessor here, added in the same commit as the Family.
 * It returns a non-null value, so an absent plugin reads as that Family's degenerate value and no
 * caller ever handles "absent". [priority] is the worked example. Where absence means something
 * *different* from the degenerate value — the bound under [Unfolding] is underspecified, not
 * defaulted — the Family says so in its own KDoc, and its degenerate value is the **underspecified**
 * member rather than a guessed one.
 *
 * The accessor is also the only way Swift reaches the Family. [plugin] and [has] are
 * `inline fun <reified T>`, and an inline function has no callable symbol in the compiled binary, so
 * it never reaches the Obj-C API surface and SKIE cannot recover it either: a generic-only reader is
 * **invisible to Swift**. `AppleFrameworkConfig` refuses a blanket name-collision suppression for
 * this package, so an accessor whose name collides with an Obj-C selector needs an `@ObjCName` at the
 * declaration. Prefer a name that does not collide.
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
     * This record's urgency bucket and pin flag. Degenerate: `Priority.Normal`, unpinned — the worked
     * example above, wrapping the shipped `Priority` enum with nothing mapped and nothing decided.
     */
    val priority: Prioritizable get() = plugin<Prioritizable>() ?: Prioritizable()

    // ── Temporal ───────────────────────────────────────────────────────────────────────────────

    /** When this is committed to happen. Degenerate: [Anchor.Unanchored] — wanted, not scheduled. */
    val anchor: Anchor get() = plugin<Anchor>() ?: Anchor.Unanchored

    /** The soft "want done by" date. Degenerate: none. */
    val targeted: Targeted get() = plugin<Targeted>() ?: Targeted()

    // ── Unfolding ──────────────────────────────────────────────────────────────────────────────

    /**
     * The rule this happens on. Degenerate: no rule — and unlike the bound under [Unfolding], that is
     * a **determinate** answer rather than an underspecified one.
     */
    val repeats: Repeats get() = plugin<Repeats>() ?: Repeats()

    // ── Enactment ──────────────────────────────────────────────────────────────────────────────

    /** Where this has got to, and when it stopped. Degenerate: [Lifecycle.Unstated], unfinished. */
    val progress: Progress get() = plugin<Progress>() ?: Progress()

    /** How the doing felt. Degenerate: unrecorded. */
    val trackable: Trackable get() = plugin<Trackable>() ?: Trackable()

    /**
     * What is on record for one date. Degenerate: **nothing on record** — which is not the stored
     * `Scheduled` an Event row can hold, and [Outcome]'s KDoc says why the two must stay apart.
     *
     * Declared on the host rather than on [Occurrence] so it reads the same on both records; an
     * [Item] never carries one, which [misplaced] is what enforces.
     */
    val outcome: Outcome get() = plugin<Outcome>() ?: Outcome()

    // ── Linkage ────────────────────────────────────────────────────────────────────────────────

    /** Readiness and the edges behind it. Degenerate: unblocked, gating nothing. */
    val blocker: Blocker get() = plugin<Blocker>() ?: Blocker()

    /** The forward hand-off edge. Degenerate: none. */
    val succeeds: Succeeds get() = plugin<Succeeds>() ?: Succeeds()

    /** Upstream provenance. Degenerate: a native Deferno item. */
    val importable: Importable get() = plugin<Importable>() ?: Importable()

    // ── Modal ──────────────────────────────────────────────────────────────────────────────────

    /**
     * How much the person wants to. Degenerate: `null` desire, reading as [Strength.Unstated] — the
     * question was never put, which is not the same as "no".
     */
    val volition: Volition get() = plugin<Volition>() ?: Volition()

    // ── The shadowed families ──────────────────────────────────────────────────────────────────
    //
    // Read on the same terms as the wire-backed ones. Which values cannot be sent is answered once,
    // by the clamp in `recipe/Clamp.kt`.

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
 * The loaded member of type [T], or `null` when the list is silent about it. An extension rather than
 * a member because Kotlin prohibits `inline` on virtual members, and without `inline reified` a
 * caller would have to pass a `KClass`. **Not for ordinary callers** — they read a total accessor.
 * This is the primitive those accessors are built from, and the escape hatch for a reader that must
 * tell absent from degenerate.
 */
inline fun <reified T : Plugin> PluginHost.plugin(): T? =
    plugins.filterIsInstance<T>().firstOrNull()

/** Whether a member of type [T] is loaded at all. See [plugin] for who may ask. */
inline fun <reified T : Plugin> PluginHost.has(): Boolean = plugin<T>() != null

/**
 * Replace whichever member of [replacement]'s exclusive family is loaded with [replacement], leaving
 * every other family untouched — **the conversion primitive**. "This Chore becomes a Task" is this
 * call: one family swaps, and content, labels, modality and history never move. The key is
 * [Plugin.family], not the concrete type, so a swap *inside* a sealed-parent family removes the
 * sibling rather than sitting beside it.
 */
fun List<Plugin>.replacingFamilyOf(replacement: Plugin): List<Plugin> =
    filterNot { it.family == replacement.family } + replacement

/**
 * Unload the whole family [member] belongs to, returning readers of it to its degenerate value.
 * Takes a **member instance** rather than a `KClass` because in a sealed-parent family the member's
 * own class is not the family key: handed `Deadline::class`, such a version would remove nothing and
 * leave the reader still returning the loaded value.
 */
fun List<Plugin>.withoutFamilyOf(member: Plugin): List<Plugin> =
    filterNot { it.family == member.family }

/**
 * Load [member], **or unload its family when [member] says nothing** — the swap an edit performs.
 *
 * It is [replacingFamilyOf] plus the sparseness rule, and the pair is not interchangeable. A list must
 * hold no plugin equal to its family's silence: that is what makes exactly one plugin list correspond to
 * a row, and therefore what makes the recipe round trip an identity rather than an equivalence. An edit
 * that clears the last field of a family — a deadline losing both its instant and its time of day — has
 * to unload the family, not load an empty member beside it.
 *
 * Every optimistic transform in `core:data`'s outbox goes through this rather than through
 * [replacingFamilyOf] directly, for exactly that reason (#422).
 */
fun List<Plugin>.loading(member: Plugin): List<Plugin> =
    if (member.saysSomething) replacingFamilyOf(member) else withoutFamilyOf(member)

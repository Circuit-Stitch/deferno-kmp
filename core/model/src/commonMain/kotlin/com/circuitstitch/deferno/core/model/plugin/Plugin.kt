@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.reflect.KClass

/**
 * Which record owns a plugin — **a field, not a type**: the [Item] definition, or one dated
 * [Occurrence] of it. Being a field makes the placement check [misplaced], a filter over this value,
 * and leaves no default, so a plugin that forgets to answer does not compile.
 */
@ObjCName("PluginScope")
enum class Scope {
    /** The [Item] owns it. One value covers the thing however many times it happens. */
    Definition,

    /** One dated [Occurrence] owns it. The value exists only *after* the engagement. */
    Occurrence,
}

/**
 * How far a plugin's value can travel — **a field, not a type**, like [Scope]. Five Family members
 * have no wire field and are held on the device instead: the bound, the verdict on whether a goal
 * obtained, the purpose edges, deontic obligation, and persistence policy. The boundary runs along
 * Family *members* — one is wire-backed or it is not, never both — so a reader filters on it: the
 * outbox never sees a [DeviceLocal] plugin, a refresh never clears one, and a surface a person can
 * act on marks one as not synced.
 */
@ObjCName("PluginReach")
enum class Reach {
    /**
     * The wire has a field for this, so it round-trips and reaches the server. The representable set
     * is pinned to exactly this, so nothing a person creates offline can fail to sync.
     */
    Wire,

    /**
     * No wire field exists. The value lives in the device-local shadow store, never reaches the
     * outbox, and is **expected to be lost** at the cutover — which is why it is surfaced as unsynced
     * wherever a person can act on it.
     */
    DeviceLocal,
}

/**
 * One slice of an [Item]'s data plus the behaviour over it (ADR-0055): the data class **is** the
 * plugin, presence in the list **is** the composition, and the set is **closed**. The sub-interfaces
 * below the divider are the eight meaning families, one conversion axis each; two shapes are
 * losslessly inter-convertible **iff they differ only in which member of one family is loaded**, and
 * today's four kinds are effectively one family, so every conversion between them is lossy. The
 * families are documentation in types — none of the eight is ever matched.
 *
 * ### The seal has teeth
 *
 * `Plugin` is sealed so that a site which must handle every member cannot silently skip a new one.
 * There is exactly one such site: the `describe` witness in `PluginSealTest`, which stops compiling.
 * The parity recipe's **write** direction is not such a site — it reads named accessors, so nothing
 * there is exhaustive over this type. A plugin the writer forgets is caught by the round-trip corpus
 * instead, and only once `KindShapes` grows an axis for the new wire field; the seal witness sweeps
 * its own samples through the clamp to cover that gap.
 *
 * Nothing else in this package branches on which plugin it is holding: placement is [misplaced] over
 * [scope], exclusivity is [exclusivityProblems] over [family], and a reader asks for the type it
 * wants through [PluginHost.plugin].
 */
@ObjCName("Plugin")
sealed interface Plugin {

    /** Which record owns this plugin. Deliberately abstract — see [Scope]. */
    val scope: Scope

    /**
     * How far this plugin's value can travel — see [Reach]. Abstract because neither default is safe:
     * [Reach.Wire] would enqueue a mutation that can never drain, [Reach.DeviceLocal] would silently
     * stop sending a Family that has a field.
     */
    val reach: Reach

    /**
     * The exclusive family this plugin belongs to; at most one member may be loaded, which
     * [exclusivityProblems] enforces. Defaults to the plugin's own type, and a plugin that genuinely
     * holds several of something holds a **list** instead. A family whose members sit under a sealed
     * parent overrides this to name that parent. It is not the meaning family above: [Unfolding] is
     * one meaning whose bound is exclusive and whose recurrence composes freely with it.
     */
    val family: KClass<out Plugin> get() = this::class

    /**
     * What this plugin's family reads as when **nothing** is loaded — what the non-generic accessor
     * on [PluginHost] returns for an absent plugin. Abstract with no default, so a new Family must
     * answer *"what does silence mean here?"*. A family whose members sit under a sealed parent
     * answers **once, on the parent**, naming the member that means silence — a value rather than a
     * type, because `Unanchored` is a real member.
     */
    val degenerate: Plugin

    /**
     * Whether this plugin claims anything beyond its family's silence. A recipe loads a plugin only
     * when this is `true`: one equal to its [degenerate] value is what an absent one already reads
     * as, so loading it would make the round trip an equivalence rather than an identity.
     * **A sealed-parent family whose members can each be empty must override this**, since the
     * default compares against [degenerate], a single *value*; `Anchor` shows the shape, an
     * exhaustive `when` on the family's own parent.
     */
    val saysSomething: Boolean get() = this != degenerate

    /** Reasons this plugin's own data is invalid. Empty when valid. */
    fun validate(): List<String> = emptyList()
}

// ── The eight meaning families ─────────────────────────────────────────────────────────────────

/** What the thing *is*: its predicate, its labels, the stuff hung on it. Wire-backed. */
@ObjCName("PluginContent")
sealed interface Content : Plugin

/** How it unfolds over time; an aspect reading derives from it. `Repeats` backed, the bound shadowed. */
@ObjCName("PluginUnfolding")
sealed interface Unfolding : Plugin

/** When it is committed to happen, and relative to what. Wire-backed. */
@ObjCName("PluginTemporal")
sealed interface Temporal : Plugin

/** Must versus want — deontic and volitive modality. Volition backed, obligation shadowed. */
@ObjCName("PluginModal")
sealed interface Modal : Plugin

/** Who is expected to do it. Never modelled client-side; the marker exists so the axis is named. */
@ObjCName("PluginParticipant")
sealed interface Participant : Plugin

/** What actually happened on one date. Records, never definitions. The verdict has no wire home. */
@ObjCName("PluginEnactment")
sealed interface Enactment : Plugin

/** What becomes of it if it is *not* done. Shadowed. */
@ObjCName("PluginPersistence")
sealed interface Persistence : Plugin

/** How it links to other Items. Purpose is shadowed. */
@ObjCName("PluginLinkage")
sealed interface Linkage : Plugin

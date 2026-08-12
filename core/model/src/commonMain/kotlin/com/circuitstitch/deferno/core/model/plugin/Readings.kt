@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
// Everything derived from which plugins are loaded — aspect, lapse, attainment and drive.
//
// **Store the evidence, derive the label** (ADR-0055): `OccurrenceState` is already a reading and
// never a stored value, and the recurrence cursor is already derived at render time. These four live
// beside `Item` because each reads a whole plugin list, and a plugin does not know what an item is.

/**
 * UMR lexical aspect — **derived from which [Unfolding] plugins are loaded, never stored.** Aspect is
 * not intrinsic to the predicate: *"practice scales"*, *"practice scales for 20 minutes"* and
 * *"practice scales daily"* are one predicate under different bounds, and converting between them
 * loads and unloads one Family while content, labels, modality and history stay put. The values form
 * a partial order with **underspecified interior nodes**, so a reader holding only *"buy milk"* emits
 * the least-committed node covering what it knows, and later evidence pushes *down*, never sideways.
 */
@ObjCName("PluginAspect")
sealed class Aspect(val label: String) {

    /** Dynamic, nothing further known. The top of the lattice, and what no bound reads as. */
    data object Process : Aspect("Process")

    /** Known to have no endpoint; still undecided between [State] and [Activity]. */
    data object AtelicProcess : Aspect("Atelic Process")

    /** Non-dynamic. A condition held, not an action performed. */
    data object State : Aspect("State")

    /** Dynamic, atelic — ongoing with no natural endpoint. */
    data object Activity : Aspect("Activity")

    /** Dynamic with an imposed bound whose endpoint is arbitrary. */
    data object Endeavor : Aspect("Endeavor")

    /** Dynamic with a natural endpoint — the goal state either obtains or does not. */
    data object Performance : Aspect("Performance")

    /** Recurring. Each doing has its own aspect; this describes the definition. */
    data object Habitual : Aspect("Habitual")

    /** The immediate parent in the lattice, or `null` at the top. */
    val parent: Aspect?
        get() = when (this) {
            Process -> null
            AtelicProcess -> Process
            Habitual -> Process
            Endeavor -> Process
            Performance -> Process
            State -> AtelicProcess
            Activity -> AtelicProcess
        }

    /**
     * Whether this sits **inside** [other] — [other] is on the path from here to the top. A reader
     * may emit any node; a later pass may replace it only with a node that narrows it.
     */
    fun narrows(other: Aspect): Boolean = generateSequence(this) { it.parent }.any { it == other }
}

/**
 * The aspect of the **definition**. [Repeats] wins here and only here: *"take the bins out weekly"*
 * is a `Habitual` item whose every doing is a `Performance`, and reading recurrence and telos at two
 * levels is how both survive.
 *
 * The test is [Repeats.hasRule], not whether a [Repeats] is loaded — the parity recipe loads one on
 * every Chore because a Chore's `cadenceMode` is non-null, so keying on presence would give a
 * rule-less Chore `Habitual` while its identically-evidenced Habit sibling read `Process`. With no
 * rule this reads [Aspect.Process] unless a [Dynamics] has been set in memory, because the bound is
 * shadowed and nothing persists it yet.
 */
fun Item.aspect(): Aspect = if (repeats.hasRule) Aspect.Habitual else aspectOf(dynamics)

/** The aspect of **one doing**, ignoring recurrence. */
fun Occurrence.aspect(): Aspect = aspectOf(dynamics)

private fun aspectOf(bound: Dynamics): Aspect = when (bound) {
    Dynamics.Unstated -> Aspect.Process
    is Dynamics.Atelic -> when (bound) {
        Dynamics.NoFinishLine -> Aspect.AtelicProcess
        Dynamics.Unbounded -> Aspect.Activity
        is Dynamics.Maintained -> Aspect.State
    }
    is Dynamics.Timeboxed -> Aspect.Endeavor
    is Dynamics.Telic -> Aspect.Performance
}

/**
 * Whether this bound may **replace** [previous] — receiver sits inside argument, the same way round
 * as [Aspect.narrows], and read through the same derivation, so a member added to [Dynamics] cannot
 * acquire a narrowing rule that disagrees with its own aspect. It constrains **ingestion, not the
 * person**: coercing one date, retargeting a timeboxed item into a recurring one, and degrading a
 * lapsed telos into a condition are all sideways moves, and all fine.
 */
fun Dynamics.narrows(previous: Dynamics): Boolean = aspectOf(this).narrows(aspectOf(previous))

/**
 * What becomes of an unresolved occurrence at its horizon. Derived from [PersistencePolicy]; nothing
 * stores it. It replaces the one bit the client reads off the item kind today.
 *
 * **Today this is one-to-one with [PersistencePolicy] and adds only a label.** Two things will
 * separate them, and neither is modelled yet:
 *
 *  - An occurrence resolves its policy through a per-date override, so one date's answer can differ
 *    from the definition's. That override channel is a later slice.
 *  - [BecomesState] is the input to an aspect transition — the bound is swapped for a maintained
 *    condition and any recurrence rule is unloaded. That is a mutation, not a reading.
 *
 * Only two of the five members are reachable under the parity seed, so the answer matches the
 * kind-derived bit by construction.
 */
@ObjCName("PluginLapse")
sealed class Lapse(val label: String) {

    /** The same occurrence, a later day. */
    data object Persists : Lapse("rolls forward")

    data object Vanishes : Lapse("gone, unrecorded")

    data object LoggedMissed : Lapse("gone, and the miss is logged")

    /** The bound is replaced with a maintained condition. */
    data class BecomesState(val condition: String) : Lapse("becomes the condition \"$condition\"")

    /** A new item is minted, pointing back at this one. */
    data class Spawns(val title: String) : Lapse("spawns \"$title\"")
}

/** What becomes of this if its horizon passes unresolved. */
fun PluginHost.atHorizon(): Lapse = when (val policy = persistence) {
    PersistencePolicy.UntilComplete -> Lapse.Persists
    PersistencePolicy.ExpiresAfterWindow -> Lapse.Vanishes
    PersistencePolicy.SkippedIfMissed -> Lapse.LoggedMissed
    is PersistencePolicy.DegradesIntoState -> Lapse.BecomesState(policy.condition)
    is PersistencePolicy.CreatesFollowUp -> Lapse.Spawns(policy.title)
}

/**
 * Whether the goal state obtained, as opposed to merely having stopped — **derived from a criterion
 * on the definition and a verdict on the date, read together.** An item that ran its 20 minutes is
 * finished and never had a goal to meet; one that stopped early is finished and did not meet its
 * goal, and a finish timestamp alone cannot tell them apart.
 *
 * The definitional question is asked **first**, so a timebox answers [Attainment.NothingToAttain]
 * whatever the date's record says; whether that pair is legal is [verdictProblems]'s job. The `when`
 * is exhaustive with no `else`, and [Dynamics.Atelic] is one branch rather than three because a
 * member declared inside it has already answered.
 */
fun satisfied(item: Item, occurrence: Occurrence): Attainment {
    val criterion = when (val bound = item.dynamics) {
        is Dynamics.Telic -> bound.criterion
        is Dynamics.Timeboxed, is Dynamics.Atelic -> return Attainment.NothingToAttain
        Dynamics.Unstated -> return Attainment.Undetermined
    }
    return when (occurrence.evaluation.obtained) {
        true -> Attainment.Obtained(criterion)
        false -> Attainment.Failed(criterion)
        null -> Attainment.Unevaluated(criterion)
    }
}

/** The verdict on a criterion, read across the two records. Derived, like [Aspect] and [Lapse]. */
@ObjCName("PluginAttainment")
sealed class Attainment(val label: String) {

    /** The bound is a timebox or atelic, so there is no goal state: stopping is all there is. */
    data object NothingToAttain : Attainment("n/a — nothing to attain")

    data object Undetermined : Attainment("undetermined — no bound is loaded")

    /** There is a criterion and nobody has evaluated it. Whether the doing stopped is another axis. */
    data class Unevaluated(val criterion: String) : Attainment("not evaluated — \"$criterion\" is outstanding")

    data class Obtained(val criterion: String) : Attainment("yes — \"$criterion\" obtained")

    data class Failed(val criterion: String) : Attainment("no — \"$criterion\" did not obtain")
}

/**
 * What makes pushing past resistance worth it — **derived from [Purpose] edges plus the modality on
 * what they point at**, and deliberately not this item's own modality, since drive is asked exactly
 * when that is weak. [Purpose] is shadowed and nothing persists it yet, so no item has a carrot and
 * every item reads [Unstated] today; the same function returns real answers once the device-local
 * store arrives.
 */
@ObjCName("PluginDrive")
sealed class Drive(val label: String) {

    /** No [Purpose] loaded, so nothing says what this is for. */
    data object Unstated : Drive("nothing says what this is for")

    /** There are carrots, but nothing says whether you want or owe any of them. */
    data class Unweighed(val carrots: List<Carrot>) :
        Drive("nothing says whether you want or must do what this is for")

    /**
     * What the chain turned up. **Two answers, never merged into one:** ranking a want against a must
     * loses the *dreaded must*, and is why volition and obligation are two plugins.
     */
    data class From(val wanted: Wanted? = null, val required: Required? = null) :
        Drive(listOfNotNull(wanted?.label, required?.label).joinToString("; "))
}

/** The most wanted thing on the chain. [Strength.None] is reported, not dropped. */
@ObjCName("PluginWanted")
data class Wanted(val toward: String, val strength: Strength) {
    val label get() = "$strength — $toward"
}

/** The most binding thing on the chain. */
@ObjCName("PluginRequired")
data class Required(val toward: String, val force: Force) {
    val label get() = "$force — $toward"
}

/**
 * The strongest want and the strongest obligation reachable by following the purpose edges. Takes a
 * [lookup] because this reads two items where [aspect] reads one and `core:model` has no store; the
 * `seen` set makes a cycle terminate.
 */
fun Item.drive(lookup: (String) -> Item?): Drive {
    val carrots = reachableCarrots(lookup)
    if (carrots.isEmpty()) return Drive.Unstated

    val targets = carrots.mapNotNull { carrot -> (carrot as? Carrot.Linked)?.itemId?.let(lookup) }
    val wanted = targets
        .filter { it.volition.strength != Strength.Unstated }
        .minByOrNull { rank(it.volition.strength) }
        ?.let { Wanted(it.core.title, it.volition.strength) }
    val required = targets
        .mapNotNull { target -> target.obligation.force?.let { Required(target.core.title, it) } }
        .minByOrNull { rank(it.force) }

    if (wanted == null && required == null) return Drive.Unweighed(carrots)
    return Drive.From(wanted, required)
}

/**
 * Nothing at the far end gives a reason to keep this — a **candidate** for a pruning pass, never a
 * verdict, because dropping is the person's decision. False for [Drive.Unstated] and
 * [Drive.Unweighed]: an unanswered question is not evidence. Only [Drive.From] came back empty.
 */
val Drive.isDropCandidate: Boolean
    get() {
        if (this !is Drive.From) return false
        val wantsIt = wanted != null && wanted.strength != Strength.None
        val owesIt = required != null && required.force != Force.May
        return !wantsIt && !owesIt
    }

private fun Item.reachableCarrots(lookup: (String) -> Item?): List<Carrot> {
    val seen = mutableSetOf(core.id)
    val out = mutableListOf<Carrot>()
    val queue = ArrayDeque(purpose.carrots)
    while (queue.isNotEmpty()) {
        val carrot = queue.removeFirst()
        out += carrot
        val next = (carrot as? Carrot.Linked)?.itemId?.takeIf { seen.add(it) }?.let(lookup) ?: continue
        queue += next.purpose.carrots
    }
    return out
}

// Strongest first on both axes, written out rather than read off `ordinal`: a rank derived from
// declaration order inverts silently the moment somebody reorders a member, and nothing would fail.

private fun rank(s: Strength): Int = when (s) {
    Strength.Strong -> 0
    Strength.Weak -> 1
    Strength.None -> 2
    Strength.Unstated -> 3
}

private fun rank(f: Force): Int = when (f) {
    Force.Must -> 0
    Force.Should -> 1
    Force.May -> 2
}

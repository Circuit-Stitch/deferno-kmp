@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlinx.datetime.LocalDate
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * An item as ADR-0055 models it: a [Core] plus a **sparse list of plugins**, and no kind anywhere.
 *
 * Nothing outside this package reads [plugins] directly — readers go through a Family's non-generic
 * accessor in `PluginHost.kt`, which returns that Family's degenerate value when nothing is loaded.
 * [validate] buys back the two guarantees named fields gave for free: at most one member of a family
 * is loaded, and a plugin sits on the record its [Scope] names.
 *
 * The Swift name is `PluginItem` because the shipped kind-blind projection `core.model.Item` already
 * owns that flat Obj-C symbol in `Deferno.framework`. It comes off at the cutover, when the projection
 * and the four kinds are deleted and this becomes the only `Item` there is.
 */
@ObjCName("PluginItem")
data class Item(
    val core: Core,
    override val plugins: List<Plugin> = emptyList(),
) : PluginHost {

    /**
     * Everything this record can be wrong about on its own. Empty when valid. The last check reads
     * two families together, so it lives on neither plugin, and it keys on whether a rule survived
     * the wire rather than on whether a [Repeats] is loaded — a Chore always loads one. What only a
     * pair can be wrong about is [problemsAcross].
     */
    fun validate(): List<String> = buildList {
        addAll(plugins.flatMap { it.validate() })
        addAll(exclusivityProblems(plugins))
        addAll(misplaced(plugins, Scope.Definition))
        addAll(unfoldingProblems(dynamics, repeats.hasRule))
    }
}

/**
 * One dated **engagement with** an [Item] — not one dated performance of its predicate. A key plus
 * facts, not a row with an identity: [itemId] and [date] are the key, the plugins are what is on
 * record, and there is no per-firing id because a Habit occurrence has never had one on the wire. How
 * a firing *went* stays a render-time reading over the fact plus coverage plus today, exactly as
 * `resolveOccurrenceState` computes it now — derived readings are never stored.
 *
 * `PluginOccurrence` in Swift for the same collision hygiene as [Item]: `core:model` already exports
 * `OccurrenceFact`, `OccurrenceState`, `OccurrenceGrid` and `OccurrenceCoverage`.
 */
@ObjCName("PluginOccurrence")
data class Occurrence(
    val itemId: String,
    val date: LocalDate,
    override val plugins: List<Plugin> = emptyList(),
) : PluginHost {

    /** Everything this record can be wrong about on its own. Empty when valid. */
    fun validate(): List<String> = buildList {
        addAll(plugins.flatMap { it.validate() })
        addAll(exclusivityProblems(plugins))
        addAll(misplaced(plugins, Scope.Occurrence))
    }
}

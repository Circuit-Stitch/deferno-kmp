@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlinx.datetime.LocalDate
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * An item as ADR-0055 models it: a [Core] plus a **sparse list of plugins**, and no kind anywhere.
 *
 * ### Why this is `PluginItem` in Swift
 *
 * `core:model` is exported into `Deferno.framework`, and Obj-C has no namespaces — so this type and
 * the shipped kind-blind projection `com.circuitstitch.deferno.core.model.Item` would collide on one
 * flat symbol and the compiler would pick a mangled spelling for one of them. `AppleFrameworkConfig`
 * deliberately refuses a blanket suppression for this package, so the collision is named at the
 * declaration instead — the same `@ObjCName` convention the four kinds' `description` already uses.
 *
 * **The annotation comes off at the cutover**, when the projection and the four kinds are deleted
 * together and this becomes the only `Item` there is. Until then two types answer for one row and
 * the Swift name says which is which.
 *
 * ### The list is sparse and every read is total
 *
 * Nothing outside this package reads [plugins] directly. Readers go through a Family's non-generic
 * accessor, which returns that Family's degenerate value when nothing is loaded — see the accessor
 * convention in `PluginHost.kt`. Two guarantees that named fields gave for free are bought back by
 * [validate] instead: that at most one member of a family is loaded, and that a plugin sits on the
 * record its [Scope] names.
 */
@ObjCName("PluginItem")
data class Item(
    val core: Core,
    override val plugins: List<Plugin> = emptyList(),
) : PluginHost {

    /** Everything this record can be wrong about on its own. Empty when valid. */
    fun validate(): List<String> = buildList {
        addAll(plugins.flatMap { it.validate() })
        addAll(exclusivityProblems(plugins))
        addAll(misplaced(plugins, Scope.Definition))
    }
}

/**
 * One dated **engagement with** an [Item] — not one dated performance of its predicate.
 *
 * ### This is the key-plus-fact shape, not a row with an identity
 *
 * ADR-0053 decision 4 removed the `Occurrence` *type* from this client on purpose: a firing is
 * identified by `(kind, definitionId, date)` and what is on record about it is an `OccurrenceFact`
 * under that key. Nothing about that is undone here. [itemId] plus [date] **is** that key with the
 * kind dropped (the kind is what the re-cut is deleting), and the plugins are what is on record —
 * `OccurrenceFact`'s stored resolution and timestamps become [Scope.Occurrence] plugins in #418.
 * There is still no per-firing id, because a Habit occurrence has never had one on the wire.
 *
 * How a firing *went* stays a render-time reading over the fact plus coverage plus today, exactly as
 * `resolveOccurrenceState` computes it now — ADR-0055's "derived readings are never stored" and
 * ADR-0053's "a reading, never a stored value" are the same rule said twice.
 *
 * ### Why `PluginOccurrence` in Swift
 *
 * The same collision hygiene as [Item]. Nothing is named plain `Occurrence` in `core:model` today,
 * but a family of `Occurrence*` types with genuinely different meanings is — `OccurrenceFact`,
 * `OccurrenceState`, `OccurrenceGrid`, `OccurrenceCoverage` — and a bare `Occurrence` sitting among
 * them in one flat Swift namespace reads as their base type, which it is not.
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

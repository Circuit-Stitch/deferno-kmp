package com.circuitstitch.deferno.core.model.plugin

/**
 * Everything a record can get wrong about *which* plugins it is carrying: a plugin sits on the record
 * its scope names, and at most one member of a family is loaded. Named fields gave both for free; a
 * list buys them back as validation, stated once here rather than at each call site. Which plugin
 * *values* may coexist is a different question, in `Coherence.kt`.
 */

/**
 * Which of [plugins] may not be written on the record named by [on]. A filter over [Plugin.scope],
 * not a `when` over plugin types: there is no branch order for correctness to depend on, and a plugin
 * that forgets to answer does not compile. Two call sites — [Item.validate] against
 * [Scope.Definition], [Occurrence.validate] against [Scope.Occurrence]. A per-date **override**
 * channel would add a third — checked against `Definition` while sitting on an Occurrence — and is
 * not modelled yet.
 */
fun misplaced(plugins: List<Plugin>, on: Scope): List<String> =
    plugins.filter { it.scope != on }
        .map { "${it::class.simpleName} loads on ${it.scope.record}, not ${on.record}" }

private val Scope.record: String
    get() = when (this) {
        Scope.Definition -> "an Item"
        Scope.Occurrence -> "an Occurrence"
    }

/**
 * At most one plugin per exclusive family. The grouping is total — every plugin is exclusive with
 * itself by [Plugin.family]'s default — so no plugin can opt out of the check by omission.
 */
fun exclusivityProblems(plugins: List<Plugin>): List<String> =
    plugins.groupBy { it.family }
        .filterValues { it.size > 1 }
        .map { (family, loaded) ->
            "${family.simpleName} is exclusive but ${loaded.size} are loaded: " +
                loaded.joinToString { it::class.simpleName ?: "?" }
        }

package com.circuitstitch.deferno.core.model.plugin

/**
 * Everything a record can get wrong about *which* plugins it is carrying.
 *
 * These two functions are the runtime cost ADR-0055 names in its consequences: the checks that buy
 * back what named fields gave for free — that a plugin sits on the record its scope names, and that
 * at most one member of a family is loaded. Both are validation rather than compilation, which is
 * the price of a list, and both are stated once here rather than at each of the three call sites.
 */

/**
 * Which of [plugins] may not be written on the record named by [on].
 *
 * **A filter, not a `when`** — the whole reason ADR-0055 makes placement a field. The shape this
 * replaces matched overlapping plugin *types*: the compiler accepted it as exhaustive while its
 * correctness depended on branch order, and a new plugin type slipped through it without a compile
 * error. A filter over [Plugin.scope] has no branch order to depend on, and a plugin that forgets to
 * answer does not compile (there is no default for [Plugin.scope]).
 *
 * Two call sites, one rule: [Item.validate] against [Scope.Definition] and [Occurrence.validate]
 * against [Scope.Occurrence]. A third arrives if the per-date **override** channel is ever modelled
 * — an override stands in for the Item's value, so it is checked against `Definition` while sitting
 * on an Occurrence — and it is deliberately not modelled yet: rescheduling one firing is a shipped
 * feature, but it is a Phase 4 concern and nothing in Phase 0 needs the channel to exist.
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
 * At most one plugin per exclusive family.
 *
 * The grouping is total: every plugin is exclusive with itself by [Plugin.family]'s default, so
 * there is nothing to filter out first and no plugin can opt out of the check by omission.
 */
fun exclusivityProblems(plugins: List<Plugin>): List<String> =
    plugins.groupBy { it.family }
        .filterValues { it.size > 1 }
        .map { (family, loaded) ->
            "${family.simpleName} is exclusive but ${loaded.size} are loaded: " +
                loaded.joinToString { it::class.simpleName ?: "?" }
        }

/**
 * A **condition may not have dates generated ahead of it**.
 *
 * Not *"a maintained condition has no dated engagements"* — it has them, chosen one at a time or
 * recorded after the fact. The offence is a rule sitting on a condition minting future rows, because
 * a queue of completable rows for something that is never completed is precisely the permanently-open
 * Task the bound axis exists to stop faking.
 */
fun unfoldingProblems(bound: Dynamics, repeats: Boolean): List<String> = buildList {
    if (bound is Dynamics.Maintained && repeats) {
        add("Maintained is a condition to hold; it cannot also repeat on a rule")
    }
}

/**
 * A verdict needs a **criterion to be a verdict on**, and the two live on different records.
 *
 * [Evaluation] says *the goal state obtained, or it did not*; which goal state is [Dynamics.Telic]'s
 * business, on the definition. So a verdict recorded against a bound stating no criterion — a
 * timebox, an atelic bound, or none at all — is not a verdict about anything, and neither record
 * alone can say so.
 *
 * What makes it a defect rather than a curiosity is that a reader would act on it: a maintained
 * condition could otherwise be terminally failed, and a condition cannot fail — it can be in breach.
 */
fun verdictProblems(bound: Dynamics, evaluation: Evaluation): List<String> = buildList {
    if (evaluation.obtained != null && bound !is Dynamics.Telic) {
        add("a verdict needs a criterion to be about; the bound for this date states none")
    }
}

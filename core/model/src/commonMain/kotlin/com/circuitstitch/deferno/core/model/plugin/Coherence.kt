package com.circuitstitch.deferno.core.model.plugin

/**
 * Which plugin *values* may coexist — the rules that read two families at once. A plugin's own
 * `validate` sees only itself, so none of these live on one: [unfoldingProblems] reads a bound against
 * a recurrence rule on one record and [Item.validate] checks it, while [verdictProblems] reads a
 * criterion on the definition against a verdict on a date, which only [problemsAcross] can ask.
 * Which plugins a record may carry at all is a different question, in `Placement.kt`.
 */

/**
 * A **condition may not have dates generated ahead of it**. It does have dated engagements — chosen
 * one at a time, or recorded after the fact; the offence is a rule minting future rows for something
 * that is never completed. [repeats] is whether a rule survived the wire, never whether a [Repeats]
 * is loaded — the same test [Item.aspect] makes, because a Chore always loads one.
 */
fun unfoldingProblems(bound: Dynamics, repeats: Boolean): List<String> = buildList {
    if (bound is Dynamics.Maintained && repeats) {
        add("Maintained is a condition to hold; it cannot also repeat on a rule")
    }
}

/**
 * A verdict needs a **criterion to be a verdict on**, and the two live on different records.
 * [Evaluation] says the goal state obtained, or it did not; which goal state is [Dynamics.Telic]'s
 * business, on the definition. A verdict against a bound that states no criterion — a timebox, an
 * atelic bound, or none — is not a verdict about anything. A reader would act on it: a maintained
 * condition could otherwise be terminally failed, and a condition cannot fail, only be in breach.
 */
fun verdictProblems(bound: Dynamics, evaluation: Evaluation): List<String> = buildList {
    if (evaluation.obtained != null && bound !is Dynamics.Telic) {
        add("a verdict needs a criterion to be about; the bound for this date states none")
    }
}

/**
 * Everything a pair can be wrong about: each record on its own, plus the rule that spans them. Neither
 * `validate` can reach [verdictProblems] — each record sees half the evidence, the same shape as
 * [satisfied].
 */
fun problemsAcross(item: Item, occurrence: Occurrence): List<String> =
    item.validate() + occurrence.validate() + verdictProblems(item.dynamics, occurrence.evaluation)

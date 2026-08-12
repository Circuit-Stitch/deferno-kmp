package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.OccurrenceResolution

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
 * **Lateness needs something to be late against**, and the two live on different records: the
 * commitment is an [Anchor] on the definition, the verdict on it an [Outcome] for one date. Being
 * late is only a distinguishable outcome under a [Anchor.Deadline] — an [Anchor.Appointment] happens
 * *at* a time and is never satisfiable after it.
 *
 * This is the kind-free restatement of a rule the wire enforces by kind: `validate_for_event` refuses
 * `DoneLate` at the event handler's boundary, and the client's own occurrence mutation branches
 * `ItemKind.Event -> DoneOnTime` to match. Stated over the anchor, it survives the kinds' deletion —
 * and it says the same thing about an item that is *anchored like an appointment* whatever it used to
 * be called.
 *
 * A disagreement *within* one firing — a stored punctuality the timestamps beside it do not support —
 * is [Outcome.punctualityDisagrees], and is a reading rather than a problem.
 */
fun latenessProblems(anchor: Anchor, outcome: Outcome): List<String> = buildList {
    if (outcome.resolution == OccurrenceResolution.DoneLate && !anchor.latenessIsMeaningful) {
        add("this happens at a time rather than by one, so a firing of it cannot be late")
    }
}

/**
 * Everything a pair can be wrong about: each record on its own, plus the rules that span them. Neither
 * `validate` can reach [verdictProblems] or [latenessProblems] — each record sees half the evidence,
 * the same shape as [satisfied].
 */
fun problemsAcross(item: Item, occurrence: Occurrence): List<String> =
    item.validate() + occurrence.validate() +
        verdictProblems(item.dynamics, occurrence.evaluation) +
        latenessProblems(item.anchor, occurrence.outcome)

package com.circuitstitch.deferno.core.model.plugin

/**
 * Volitive modality — how much the person wants to do this.
 *
 * ### [desire] is continuous, and that is not negotiable
 *
 * The reference model types this as a three-valued `{Strong, Weak, None}` enum. This client stores
 * `desire: Double?` and flows it through the ADR-0041 Backup file mappers — `Task` →
 * `ItemView.Task` on export, `ItemView.Task` → `CreateTaskPayload` on import — so bucketing to three
 * values would be lossy **and** would break the export round trip for every item already carrying
 * one. The captured fixtures even hold values outside `0.0..1.0` (only the Agent's extractor clamps,
 * and it clamps by dropping), so the representable set is wider than any bucketing would admit.
 *
 * So the `Double?` is carried and the three-valued reading is **derived** — [strength] — the same way
 * aspect is derived rather than stored. Nothing is lost, and a surface that wants three buckets asks
 * for them.
 *
 * ### Task-only today, and absence is a real answer
 *
 * Only `Task` carries `desire` on the wire; a Habit, Chore or Event never loads this plugin and
 * reads the degenerate `Volition()`. That degenerate is `desire = null`, which is *"nobody was
 * asked"* — distinct from `0.0`, which is *"asked, and no"*. [Strength.Unstated] keeps those apart,
 * because collapsing them is what makes a drop-candidate sweep read an unanswered question as
 * evidence.
 *
 * ### Obligation is not here
 *
 * The other half of [Modal] — deontic obligation, the need-versus-want answer `capture_item` already
 * asks and discards — has no wire field anywhere and is shadowed under ADR-0057. It lands in #419.
 */
data class Volition(val desire: Double? = null) : Modal {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Volition()

    /**
     * The three-valued reading over [desire] — **derived, never stored**.
     *
     * The thresholds are this reading's own and are not a wire contract: nothing on the wire, in the
     * database or in the backup file has ever bucketed `desire`, so there is no existing split to be
     * faithful to. They are stated here so a surface does not invent its own, and moving them
     * changes no stored data.
     *
     * The comparison is over the raw value with no clamping, because the fixtures carry negatives
     * and a negative is not "weak" — it is below anything the scale means, and it reads as [None]
     * rather than being silently pulled up to zero.
     */
    val strength: Strength
        get() = when {
            desire == null -> Strength.Unstated
            desire >= STRONG_AT -> Strength.Strong
            desire > NONE_BELOW -> Strength.Weak
            else -> Strength.None
        }

    companion object {
        /** At or above this, wanting it is doing the work. */
        const val STRONG_AT: Double = 0.66

        /** At or below this, nothing is pulling. `0.0` is a claim, and it lands here. */
        const val NONE_BELOW: Double = 0.0
    }
}

/**
 * Volitive force as a surface reads it. Four members, not the reference model's three, because
 * *nobody was asked* is a different answer from *asked, and no* — see [Volition.strength].
 */
enum class Strength {
    /** No [Volition] loaded, or one carrying no value. The question was never put. */
    Unstated,

    /** Asked, and nothing is pulling. */
    None,

    /** Asked, and something is — thinly. */
    Weak,

    /** Asked, and wanting it is doing the work. */
    Strong,
}

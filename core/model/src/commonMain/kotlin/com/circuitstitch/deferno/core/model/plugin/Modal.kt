@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
/**
 * Volitive modality — how much the person wants to do this.
 *
 * [desire] is continuous, not bucketed: the Backup file mappers flow the raw `Double?` through export
 * (`Task` → `ItemView.Task`) and import (`ItemView.Task` → `CreateTaskPayload`), so three-valuing it
 * would be lossy and would break the round trip for every item already carrying one, and the captured
 * fixtures even hold values outside `0.0..1.0`. The three-valued reading is derived — [strength].
 *
 * Task-only today: a Habit, Chore or Event never loads this plugin and reads the degenerate
 * `Volition()`, whose `desire = null` is *nobody was asked* — distinct from `0.0`, *asked, and no*,
 * which [Strength.Unstated] keeps apart. The other half of [Modal], deontic obligation, has no wire
 * field anywhere and is shadowed under ADR-0057.
 */
@ObjCName("PluginVolition")
data class Volition(val desire: Double? = null) : Modal {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Volition()

    /**
     * The three-valued reading over [desire] — **derived, never stored**. The thresholds are this
     * reading's own and not a wire contract; nothing that persists `desire` buckets it. The comparison
     * is unclamped, so a negative reads as [None] rather than being pulled up to zero.
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
 * Volitive force as a surface reads it. Four members, because *nobody was asked* is a different answer
 * from *asked, and no* — see [Volition.strength].
 */
@ObjCName("PluginStrength")
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

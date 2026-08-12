@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.reflect.KClass
import kotlin.time.Instant

// The five Family members the four-kind wire cannot carry (ADR-0057).
//
// They sit across five meaning families — Unfolding, Enactment, Linkage, Modal, Persistence — and
// share a *reach* instead: every one is `Reach.DeviceLocal`, none reaches the outbox, and all five
// are dropped together at the cutover. **Types and total-read accessors only in this slice**: the
// device-local table is a later slice, so a shadowed value survives in memory and is lost on restart.

/**
 * The **bound**: whether one doing of this thing has an endpoint, and what sort. The family a UMR
 * aspect reading derives from, and the one that makes *"keep inbox below 20"* expressible at all;
 * today that shape has nowhere to live and is faked as a permanently-open Task.
 *
 * Absence here is **underspecified, not defaulted**: an item with no [Dynamics] loaded reads as
 * [Aspect.Process], "this is dynamic" and nothing further. [Repeats] is the contrast — its absence is
 * a *determinate* answer, since no rule means it does not repeat. The degenerate value here is the
 * underspecified member, [Unstated].
 *
 * [Atelic] is a **type, not a member**, so *sits within* is a fact about the declaration rather than
 * one repeated in every `when` — which is what lets [narrows] read the lattice instead of a table.
 */
@ObjCName("PluginDynamics")
sealed class Dynamics : Unfolding {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val family: KClass<out Plugin> get() = Dynamics::class
    override val degenerate: Plugin get() = Unstated

    /** Nobody has said. The degenerate value — an absence of claim, not a claim of absence. */
    data object Unstated : Dynamics()

    /** The branch with no finish line. A type rather than a member — see the class KDoc. */
    sealed class Atelic : Dynamics()

    /**
     * No finish line — and **not yet which sort of no**. *"Stay on top of the laundry"* settles that
     * much and no more; whether it is a condition held ([Maintained]) or an activity done
     * ([Unbounded]) is a further question, and a guess would have to be retracted sideways later.
     */
    data object NoFinishLine : Atelic()

    /** Ongoing, no endpoint. Sessions, done regularly, with nothing that would end them. */
    data object Unbounded : Atelic()

    /**
     * A condition to be **held** rather than an action to be performed. *"Keep inbox below 20"* can
     * still be wanted, prioritised, discussed and breached, but nothing about it obtains and then
     * stops. It has dated engagements like anything else; what it may not have is dates **generated
     * ahead of it**, which is what [unfoldingProblems] rejects.
     */
    data class Maintained(val condition: String) : Atelic()

    /** A bound whose endpoint is arbitrary — you stop because the time ran out, not because a goal obtained. */
    data class Timeboxed(val minutes: Int) : Dynamics()

    /** A natural endpoint: a criterion that either obtains or does not. **This is the telos.** */
    data class Telic(val criterion: String) : Dynamics()
}

/**
 * The **verdict** on the criterion for one date — attainment, recorded. Separate from a finish
 * timestamp because stopping and attaining are different claims; without it, *"I tried and it didn't
 * work"* has nowhere to live. **Absent, never `false`, when nobody has evaluated it**: three states,
 * and the degenerate value is "not evaluated".
 *
 * The only [Scope.Occurrence] plugin here. The criterion is definitional and lives on
 * [Dynamics.Telic], so a verdict recorded against a bound stating no criterion is a verdict about
 * nothing — which no single record can notice, and which [verdictProblems] catches.
 */
@ObjCName("PluginEvaluation")
data class Evaluation(val obtained: Boolean? = null) : Enactment {
    override val scope get() = Scope.Occurrence
    override val reach get() = Reach.DeviceLocal
    override val degenerate get() = Evaluation()
}

/**
 * What this item is **for** — the purpose edges [Drive] reads. A list, because one thing can serve
 * several ends. The client has no field for any of it today, so every item reads no carrots and
 * [Drive] answers `Unstated` until the shadow store lands.
 */
@ObjCName("PluginPurpose")
data class Purpose(val carrots: List<Carrot> = emptyList()) : Linkage {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val degenerate get() = Purpose()

    override fun validate(): List<String> = buildList {
        if (carrots.any { it is Carrot.InWords && it.prose.isBlank() }) {
            add("a carrot stated in words cannot be blank")
        }
    }
}

/**
 * What makes an item worth doing — one of two things, never both and never neither. Sealed rather
 * than two nullable fields, so neither *neither* nor *both* is representable.
 */
@ObjCName("PluginCarrot")
sealed interface Carrot {

    /** The carrot is another item. */
    data class Linked(val itemId: String) : Carrot

    /**
     * The carrot is only words, because no item stands for it — *"go to Japan"* need not be a task to
     * be the reason you renew the passport. Becoming [Linked] later takes nothing back.
     */
    data class InWords(val prose: String) : Carrot
}

/**
 * Deontic modality — how obligatory this is. `CaptureInput` **already asks this question**, puts it
 * at the surface, derives a kind from the answer and then has nowhere to record it. This is where it
 * lands.
 */
@ObjCName("PluginObligation")
data class Obligation(val force: Force? = null) : Modal {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val degenerate get() = Obligation()
}

/** Deontic force, weakest binding to strongest. `null` on [Obligation] means the question was never put. */
@ObjCName("PluginForce")
enum class Force { May, Should, Must }

/**
 * What becomes of an occurrence that reaches its horizon **unresolved** — five policies on their own
 * axis, where the client today derives one bit from the item kind. That bit is wrong on arity and on
 * source: the temporal anchor does not determine the policy, since one deadline can persist, expire,
 * or spawn a follow-up.
 *
 * `PersistenceSeed` maps today's bit onto this family — carries-forward seeds [UntilComplete], lapses
 * seeds [ExpiresAfterWindow]. **Nothing else is seeded**, so no behaviour changes and the richer
 * three stay unreachable. [SkippedIfMissed] in particular is not what a Habit seeds: logging a miss is
 * a stronger claim than today's bit makes.
 */
@ObjCName("PluginPersistencePolicy")
sealed class PersistencePolicy : Persistence {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val family: KClass<out Plugin> get() = PersistencePolicy::class
    override val degenerate: Plugin get() = UntilComplete

    /**
     * Stays visible until done, rolling forward day after day. **The degenerate value, and a default
     * rather than an underspecified answer** — unlike [Dynamics.Unstated] it asserts something, and it
     * matches what a Task and a Chore do today.
     */
    data object UntilComplete : PersistencePolicy()

    /** Disappears once its time or date passes. **Nothing is recorded** — which is what lapsing is today. */
    data object ExpiresAfterWindow : PersistencePolicy()

    /**
     * Today's occurrence vanishes and the miss is **logged**. Not a refusal: a refusal is a deliberate
     * outcome a person chose, a miss is a passive lapse. Unreachable — see the class KDoc.
     */
    data object SkippedIfMissed : PersistencePolicy()

    /**
     * The telos lapses and what remains is a condition — an aspect transition, `Performance → State`.
     * *"Renew the passport by Friday"* past Friday is the standing condition *"passport expired"*.
     * Unreachable — see the class KDoc.
     */
    data class DegradesIntoState(val condition: String) : PersistencePolicy()

    /**
     * The miss mints a new item bearing an edge back to this one — a missed appointment becomes
     * *"reschedule appointment"*, a different predicate with a different aspect. Unreachable.
     */
    data class CreatesFollowUp(val title: String) : PersistencePolicy()
}

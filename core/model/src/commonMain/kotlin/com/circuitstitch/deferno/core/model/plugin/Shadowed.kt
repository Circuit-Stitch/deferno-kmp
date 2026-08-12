package com.circuitstitch.deferno.core.model.plugin

import kotlin.reflect.KClass
import kotlin.time.Instant

// The five Family members the four-kind wire cannot carry (ADR-0057).
//
// They are gathered in one file because what they have in common is not a meaning family — they sit
// across four different ones — but a *reach*: every one of them is `Reach.DeviceLocal`, none reaches
// the outbox, and all five are dropped together at the cutover. Keeping them in one place is what
// makes "which parts of the model is this device holding alone?" a question a reader can answer by
// opening a file.
//
// **Types and total-read accessors only in this slice.** The device-local table itself is a later
// slice, so nothing here persists yet: a shadowed value survives in memory and is lost on restart.
// That is deliberate sequencing — the clamp and the readings can be built and gated before there is
// a store to get wrong.

/**
 * The **bound**: whether one doing of this thing has an endpoint, and what sort.
 *
 * The family a UMR aspect reading is derived from, and the one that makes *"keep inbox below 20"*
 * expressible at all. Today that shape has nowhere to live and is faked as a permanently-open Task.
 *
 * ### Absence here is underspecified, not defaulted
 *
 * An item with no [Dynamics] loaded reads as [Aspect.Process] — "this is dynamic", and nothing
 * further. That is deliberately different from [Repeats], whose absence is a *determinate* answer
 * (no rule means it does not repeat). Two kinds of absence, and the degenerate value below is the
 * underspecified one rather than a guess: [Unstated] claims nothing.
 *
 * ### The family is not flat, and that is load-bearing
 *
 * [Atelic] is a **type, not a member** — three bounds that all claim *"nothing here obtains and then
 * stops"* and differ only in how much more they claim. Declaring them inside it makes *sits within*
 * a fact about the declaration rather than a fact repeated in every `when` that reads it, which is
 * what lets [narrows] read the lattice instead of a hand-written table.
 */
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
     * No finish line — and **not yet which sort of no**.
     *
     * *"Stay on top of the laundry"* settles that much and no more: whether that is a condition held
     * ([Maintained]) or an activity done ([Unbounded]) is a further question, and a reader that
     * guessed would have to move **sideways** later, which is a retraction rather than a narrowing.
     */
    data object NoFinishLine : Atelic()

    /** Ongoing, no endpoint. Sessions, done regularly, with nothing that would end them. */
    data object Unbounded : Atelic()

    /**
     * A condition to be **held** rather than an action to be performed.
     *
     * *"Keep inbox below 20"*: nothing about it obtains and then stops, and it can still be wanted,
     * prioritised, discussed and breached. Unrepresentable today — the client fakes it as a
     * permanently-open Task, which is the defect this member exists to stop faking.
     *
     * It has dated engagements like anything else. What it may not have is dates **generated ahead
     * of it**, which is what [unfoldingProblems] rejects.
     */
    data class Maintained(val condition: String) : Atelic()

    /** A bound whose endpoint is arbitrary — you stop because the time ran out, not because a goal obtained. */
    data class Timeboxed(val minutes: Int) : Dynamics()

    /** A natural endpoint: a criterion that either obtains or does not. **This is the telos.** */
    data class Telic(val criterion: String) : Dynamics()
}

/**
 * The **verdict** on the criterion for one date — attainment, recorded.
 *
 * Separate from a finish timestamp because stopping and attaining are different claims: sit the
 * driving test, fail it, record leaving at 11am, and a reader that consults only the timestamp hands
 * you a licence. Without this member *"I tried and it didn't work"* has nowhere to live.
 *
 * **Absent, never `false`, when nobody has evaluated it.** Three states, and the middle one is a
 * real answer: not evaluated, evaluated and did not obtain, evaluated and obtained. The degenerate
 * value is the first.
 *
 * The only [Scope.Occurrence] plugin in this file: a verdict is something that happened on a date,
 * where the criterion it is about is definitional and lives on [Dynamics.Telic]. A verdict recorded
 * against a bound stating no criterion is not a verdict about anything, which no single record can
 * notice — see [verdictProblems].
 */
data class Evaluation(val obtained: Boolean? = null) : Enactment {
    override val scope get() = Scope.Occurrence
    override val reach get() = Reach.DeviceLocal
    override val degenerate get() = Evaluation()
}

/**
 * What this item is **for** — the purpose edges [Drive] reads.
 *
 * A list, because one thing can serve several ends. Today the client has no field for any of it, so
 * every item reads no carrots and [Drive] answers `Unstated` until the shadow store lands.
 */
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
 * What makes an item worth doing — one of two things, never both and never neither.
 *
 * Sealed rather than two nullable fields: *neither* was a state only a runtime check could reject,
 * and *both* let stale prose outrank the item it had since been resolved to.
 */
sealed interface Carrot {

    /** The carrot is another item. */
    data class Linked(val itemId: String) : Carrot

    /**
     * The carrot is only words, because no item stands for it — *"go to Japan"* need not be a task
     * to be the reason you renew the passport. Becoming [Linked] later takes nothing back.
     */
    data class InWords(val prose: String) : Carrot
}

/**
 * Deontic modality — how obligatory this is.
 *
 * The capture flow **already asks this question** and throws the answer away: `CaptureInput` puts
 * the need-versus-want question at the surface, derives a kind from it, and then has nowhere to
 * record what was said. This plugin is where it lands. Whether capture should start keeping the
 * answer is a #420 decision; that it *could* is what this member establishes.
 */
data class Obligation(val force: Force? = null) : Modal {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val degenerate get() = Obligation()
}

/** Deontic force, weakest binding to strongest. `null` on [Obligation] means the question was never put. */
enum class Force { May, Should, Must }

/**
 * What becomes of an occurrence that reaches its horizon **unresolved** — five policies on their own
 * axis, where the client today derives one bit from the item kind.
 *
 * The one-bit answer is wrong twice over. **Arity**: five policies pressed into a boolean.
 * **Source**: the temporal anchor does not determine the policy — one deadline can persist (a bill),
 * expire (prep notes for a meeting that already happened), or spawn a follow-up (a missed
 * appointment). Same anchor, three policies, which is the identical mistake as reading it off the
 * kind, one axis over.
 *
 * ### Only two members are reachable today, and that is the parity seed
 *
 * `PersistenceSeed` maps the one bit onto this family: carries-forward seeds [UntilComplete] and
 * lapses seeds [ExpiresAfterWindow]. **Nothing else is seeded**, so no behaviour changes — the
 * richer three stay unreachable until #420 ratifies what each kind should actually claim. In
 * particular [SkippedIfMissed] is *not* what a Habit seeds: logging a miss is a stronger claim than
 * today's bit makes, and seeding it would start writing history nobody asked for.
 */
sealed class PersistencePolicy : Persistence {

    override val scope get() = Scope.Definition
    override val reach get() = Reach.DeviceLocal
    override val family: KClass<out Plugin> get() = PersistencePolicy::class
    override val degenerate: Plugin get() = UntilComplete

    /**
     * Stays visible until done. Rolls forward day after day.
     *
     * **The degenerate value, and a default rather than an underspecified answer** — unlike
     * [Dynamics.Unstated], this asserts something: an unresolved doing rolls forward. That matches
     * what a Task and a Chore do today, which is the majority of what exists.
     */
    data object UntilComplete : PersistencePolicy()

    /** Disappears once its time or date passes. **Nothing is recorded** — which is what lapsing is today. */
    data object ExpiresAfterWindow : PersistencePolicy()

    /**
     * Today's occurrence vanishes and the miss is **logged**.
     *
     * Not a refusal: a refusal is a deliberate outcome a person chose, a miss is a passive lapse.
     * Unreachable until #420 — see the class KDoc.
     */
    data object SkippedIfMissed : PersistencePolicy()

    /**
     * The telos lapses and what remains is a condition — an aspect transition, `Performance → State`.
     *
     * *"Renew the passport by Friday"* past Friday is no longer a thing to do by Friday; it is the
     * standing condition *"passport expired"*. Unreachable until #420.
     */
    data class DegradesIntoState(val condition: String) : PersistencePolicy()

    /**
     * The miss mints a new item bearing an edge back to this one — a missed appointment becomes
     * *"reschedule appointment"*, a different predicate with a different aspect. Unreachable
     * until #420.
     */
    data class CreatesFollowUp(val title: String) : PersistencePolicy()
}

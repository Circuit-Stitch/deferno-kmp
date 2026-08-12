@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Dynamics
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The **ratified target** for each [com.circuitstitch.deferno.core.model.plugin.Plugin] Family —
 * what the model should say once parity is no longer the constraint (#420).
 *
 * [ParityRecipe] is the other half of the pair ADR-0056 mandates, and the two sit here side by side
 * deliberately: parity reproduces today's behaviour and gates the migration, this records where each
 * Family is going so Phase 4 has something to build toward.
 *
 * **Nothing consumes this.** Like [ParityRecipe] it has no callers outside `commonTest` until the read
 * facade lands, so ratifying a target changes no behaviour. It deliberately does **not** implement
 * [KindRecipe]: three of the eight targets below cannot round-trip against the four-kind wire at all,
 * so a `KindRecipe` here would be a type asserting something false. The target recipe grows into that
 * interface one Family at a time, which is ADR-0056's own sequencing.
 *
 * ### The eight Families
 *
 * Five targets are **identical to parity** and are recorded here so that is a decision rather than an
 * omission:
 *
 * - **Content.** Unchanged. `Priority` is already `Fire`/`Normal`/`Backlog` on both sides.
 * - **Temporal.** Unchanged — [Anchor.Deadline] and [Anchor.Appointment] *are* the correction the
 *   conflation needed, and the parity recipe already makes it. The residual defect is that a
 *   conversion reinterprets a time-of-day, and that is server-side: convert is online-only
 *   (`CommandKind.ConvertItem`) and `ConvertItemPayload` carries no time-of-day field for a client to
 *   state one with. Filed upstream. What *is* decided here is [allDayAsRendered] and
 *   [clockTimesAreCruft].
 * - **Unfolding.** No kind seeds a bound — see [BOUND_SEED]. `Repeats` is unchanged.
 * - **Modal.** [com.circuitstitch.deferno.core.model.plugin.Volition] stays wire-backed and Task-only.
 *   Widening `desire` client-side is not available: `reach` is one field on the plugin and
 *   `Plugin.reach`'s KDoc requires a member be wire-backed or not, never both, so `Clamp.admit`
 *   refuses a Habit carrying one. It widens for free when the wire does, and the cutover dissolves the
 *   question either way, so no client work is planned.
 *   [com.circuitstitch.deferno.core.model.plugin.Obligation] stays unwritten: `CaptureInput` is
 *   ADR-0036's *external assistant* contract, so an answer given to Siri or an MCP agent could never
 *   reach this device's shadow store. The capture question it does ask lands on Persistence instead —
 *   see below.
 * - **Participant.** Never modelled client-side. Nothing to target.
 *
 * Three change, and each names the user-visible consequence at its declaration: Persistence
 * ([persistenceAtHorizon]), Enactment's lifecycle ([definitionLifecycleFor]) and Unfolding's
 * per-doing bound, which needs the per-date override channel `Placement.kt` already anticipates.
 */
@ObjCName("PluginTargetRecipe")
object TargetRecipe {

    // ── Persistence ────────────────────────────────────────────────────────────────────────────

    /**
     * What becomes of an unresolved firing at its horizon — **read off the definition's light switch,
     * not off the item's kind.**
     *
     * `PersistenceSeed` derives this from [com.circuitstitch.deferno.core.model.ItemKind] and justifies
     * the Habit/Event half with "habits and events are never overdue". **That claim is false in this
     * client**, and two shipped readings say so:
     *
     * - `resolveOccurrenceState` gives a past firing with no stored fact `Missed` on an Active
     *   definition and `Skipped` on a shelved one, with **no kind branch anywhere**. A missed Habit
     *   day, Chore day and Event day are treated identically, and the discriminator is the light
     *   switch.
     * - `RecurrenceCursor` renders a missed Habit as overdue since the day its cursor stopped
     *   advancing — pinned in `RecurrenceCursorTest` against live item #277.
     *
     * So a lapsing kind seeding [PersistencePolicy.ExpiresAfterWindow] ("gone, **nothing recorded**")
     * contradicts what the interface already shows. [PersistencePolicy.SkippedIfMissed] ("gone, and the
     * miss is **logged**") is the reading that matches — and under ADR-0055's own rule, *store the
     * evidence and derive the label*, a miss that is derived and rendered is what "logged" has to mean.
     * ADR-0056 named seeding it the trap to avoid; that warning rested on "nothing logs a miss today",
     * which is the part that does not hold.
     *
     * **User-visible change: none.** This is the model catching up with the interface. Every arm below
     * returns what a surface already renders for that row.
     *
     * ### This is one of two coordinates
     *
     * Persistence answers two questions that today's one bit runs together, and only the first is
     * decided here:
     *
     *  1. *What does the past day show?* — this function. Light-switch-derived, kind-blind, and
     *     already computed on-device.
     *  2. *Does the undone thing follow me into today?* — the carry-forward coordinate. Still
     *     **fetched**: `OfflinePlanRepository.refreshPlan` pulls the day's snapshot and full-replaces,
     *     so what a person sees is a server-derived answer this client caches rather than recomputes.
     *     Porting it offline the way `nextDeadlineAfter` ports cadence advancement — the Rust as the
     *     parity oracle — is its own issue, and the kind-derived bit stays the placeholder until then.
     *
     * Keyed on [Lifecycle] rather than a nullable `DefinitionState` on purpose: a Task genuinely has no
     * light switch, and a nullable would run that together with "the definition is not cached", which
     * is the distinct third answer `resolveOccurrenceState` returns `Unknown` for.
     */
    fun persistenceAtHorizon(lifecycle: Lifecycle): PersistencePolicy = when (lifecycle) {
        // A Task's `completeBy` is a plain deadline, never a series cursor (`RecurrenceCursor`), and a
        // Task with a past one stays on the plan and reads overdue. That is `Persists`, uncontested.
        // This arm dies at the cutover, when `Working` stops existing — see [definitionLifecycleFor].
        is Lifecycle.Working -> PersistencePolicy.UntilComplete

        is Lifecycle.Definition -> when (lifecycle.state) {
            // Live: the past empty day reads `Missed`, which is a reproach the surface renders.
            DefinitionState.Active -> PersistencePolicy.SkippedIfMissed
            // Shelved: `resolveOccurrenceState` gives these `Skipped` — "a shelved definition's past
            // empty days are history, not a reproach". Nothing is recorded against the person.
            DefinitionState.InReview, DefinitionState.Archived -> PersistencePolicy.ExpiresAfterWindow
        }

        // No lifecycle stated. The family's own degenerate value, unchanged from parity.
        Lifecycle.Unstated -> PersistencePolicy.UntilComplete
    }

    /**
     * Whether a policy is reachable under the ratified target. Four of five, and the exclusion is a
     * decision rather than an oversight.
     *
     * [PersistencePolicy.DegradesIntoState] is ratified: *"renew the passport by Friday"* past Friday
     * is the standing condition *"passport expired"*. Both families it touches are
     * [com.circuitstitch.deferno.core.model.plugin.Reach.DeviceLocal], so the transition is wholly a
     * shadow-store affair and never reaches the wire. Its recurrence rule **cannot** survive it —
     * `unfoldingProblems` rejects a `Maintained` bound sitting beside a rule — so a yearly renewal
     * degrading into a condition unloads the yearly rule, and the next renewal is a fresh telos rather
     * than the next firing of the old one.
     *
     * **User-visible change: new.** A lapsed telic item can be offered a degrade, and accepting one
     * unloads its recurrence. Offered and confirmed, never automatic (ADR-0027's propose-only posture);
     * a sweep finds the due items and raises an event, so nobody has to open a detail view to discover
     * the offer.
     *
     * [PersistencePolicy.CreatesFollowUp] is **deferred**, for three reasons that are all structural:
     * it would mint a wire-backed row from a device-local policy, which is the one direction ADR-0057
     * forbids; its back-edge is `Carrot.Linked` on a shadowed `Purpose`, so the link dies at the device
     * boundary too; and nothing in this client runs on a clock tick, so a miss has no moment at which
     * to fire.
     */
    fun isReachable(policy: PersistencePolicy): Boolean = policy !is PersistencePolicy.CreatesFollowUp

    // ── Unfolding ──────────────────────────────────────────────────────────────────────────────

    /**
     * The bound every item starts with: **none**. No kind seeds one.
     *
     * `Dynamics.Unstated` means *nobody has said*, and nobody has: this client has never had a field,
     * a question or an inference for the bound, so any per-kind seed would be a guess dressed as a
     * migration. The issue's own framing concedes it — *"a Habit is not obviously unbounded and a Chore
     * is not obviously telic"* — and the reason is that those are claims about **each doing**, while a
     * recurring definition reads `Habitual` whatever its bound. Seeding one onto a definition puts it
     * at the level that cannot carry the meaning.
     *
     * A wrong seed is also not free to undo. `Dynamics.narrows` constrains *ingestion, not the person*,
     * so a human can always move a bound sideways — but no automated pass can. Seeding `Unbounded`
     * (`Activity`) onto something that was really `Maintained` (`State`) leaves only hand correction,
     * one item at a time.
     *
     * **User-visible change: none.** Every item keeps reading `Habitual` when a rule survived the wire
     * and `Process` otherwise, exactly as parity.
     */
    val BOUND_SEED: Dynamics = Dynamics.Unstated

    // ── Temporal ───────────────────────────────────────────────────────────────────────────────

    /**
     * All-day as a surface should render it: **the stored flag**, so every client agrees on one answer.
     *
     * The server derives `all_day` from the two clock times being null, ignores it on input, and still
     * ships the column — so a row whose flag disagrees with the times beside it is representable, and
     * such rows exist. [Anchor.Appointment.isAllDay] is the derived reading and stays available; it is
     * not what a surface renders.
     */
    fun allDayAsRendered(appointment: Anchor.Appointment): Boolean = appointment.allDayFlag

    /**
     * Whether this Appointment's stored clock times are **cruft** — the flag says all-day and there are
     * times beside it anyway.
     *
     * Trusting the flag makes this the disagreement's resolution rather than evidence against it: the
     * times are what is wrong, and clearing them converges the two, because a row with no clock times
     * is one the server then derives `all_day = true` for.
     *
     * **User-visible change: real, and it needs its own issue** — an Event that shows a time today may
     * stop showing one. Note the asymmetry: only this direction has a safe repair. A flag reading
     * *false* beside no clock times cannot be cleaned, because nothing says what the times should have
     * been; that one can only be asked.
     */
    fun clockTimesAreCruft(appointment: Anchor.Appointment): Boolean =
        appointment.allDayFlag && !appointment.isAllDay

    // ── Enactment ──────────────────────────────────────────────────────────────────────────────

    /**
     * Today's two lifecycles re-cut onto one, **by scope rather than by name**.
     *
     * `WorkingState` runs two scopes together. `InProgress` and `Done` are claims about **one doing**,
     * and the model already holds them there as `OccurrenceState.InProgress` / `DoneOnTime` /
     * `DoneLate`; `Open` and `Dropped` are claims about the item's own life. A Task carries both only
     * because a Task has no occurrences today — and under ADR-0055 it gets them, since an `Occurrence`
     * is keyed on `itemId + date` with no kind involved.
     *
     * So the target keeps one **definition** lifecycle, `Active`/`InReview`/`Archived`, and moves the
     * per-doing claims to the record that owns them:
     *
     * | today | target | the doing |
     * |---|---|---|
     * | `Open` | `Active` | — |
     * | `InProgress` | `Active` | the firing carries `InProgress` |
     * | `InReview` | `InReview` | — |
     * | `Done` | `Active` | the firing carries `DoneOnTime` / `DoneLate` |
     * | `Dropped` | `Archived` | — |
     *
     * Keeping the two sealed arms was the alternative, and it is worse than it looks: which arm a row
     * gets would still be decided by the kind it used to be, so the kind would outlive its own
     * deletion — the thing #416 exists to remove.
     *
     * **This cannot be built yet.** `Progress` is
     * [com.circuitstitch.deferno.core.model.plugin.Reach.Wire], so a merged lifecycle has to round-trip
     * and a Task must come back a `WorkingState`; `Clamp.admit` refuses anything else. This is a shape
     * ratified for the cutover, not code with a caller.
     *
     * **User-visible changes, both real.** `Dropped` and `Archived` collapse, so a dropped Task and an
     * archived Habit stop being distinguishable. And a finished Task's *definition* is no longer
     * "Done" — doneness becomes a fact about its one firing, so terminality turns into a reading over
     * the light switch plus the firings rather than a stored state, which is the same move
     * `OccurrenceState` already makes.
     *
     * `InReview` is carried across unexamined on purpose: `ItemState.kt` records that the definition's
     * is "retained faithfully pending a backend clarification (ADR-0011)", so both sides of the merge
     * are a member nobody has interpreted. The clarification is filed upstream, and its target is
     * ratified only once answered.
     */
    fun definitionLifecycleFor(state: WorkingState): DefinitionState = when (state) {
        WorkingState.Open, WorkingState.InProgress, WorkingState.Done -> DefinitionState.Active
        WorkingState.InReview -> DefinitionState.InReview
        WorkingState.Dropped -> DefinitionState.Archived
    }
}

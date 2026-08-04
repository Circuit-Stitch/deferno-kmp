package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.DerivedChoreOccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.OccurrenceStatusWire
import com.circuitstitch.deferno.core.network.dto.PriorityWire
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire

/**
 * The wire-status → domain-state condensation (ADR-0011 "condense at the edge", CONTRACT-NOTES →
 * "Status"). The wire's six overloaded "status" enums collapse here into the distinctly-named domain
 * enums in `core:model`. Every `...Wire.Unknown` (the coerced additive-token fallback) degrades to a
 * **safe default** so a row the backend statuses additively stays usable rather than disappearing or
 * crashing the reader.
 *
 * The occurrence family condenses to **one** axis here, and that is the point (ADR-0053 decision 4):
 * `toResolution`/`toResolutionOrNull` produce the *stored* [OccurrenceResolution], the only half of a
 * firing that may reach a row. The other half — the render-time *reading* [OccurrenceState], which may
 * say `Missed` — is not condensable from a wire token and has no mapper in this file; it is derived in
 * `core:model` from the fact plus coverage plus today. The reading is never persisted; the fact is
 * never re-derived.
 */

/**
 * `TaskStatus` → [WorkingState]. [TaskStatusWire.Unknown] degrades to [WorkingState.Open] so an
 * additively-statused Task stays visible as active work rather than being hidden as terminal.
 */
fun TaskStatusWire.toWorkingState(): WorkingState = when (this) {
    TaskStatusWire.Open -> WorkingState.Open
    TaskStatusWire.InProgress -> WorkingState.InProgress
    TaskStatusWire.InReview -> WorkingState.InReview
    TaskStatusWire.Done -> WorkingState.Done
    TaskStatusWire.Dropped -> WorkingState.Dropped
    TaskStatusWire.Unknown -> WorkingState.Open
}

/**
 * [WorkingState] → its wire `TaskStatus` token — the **write** direction (ADR-0011 "the wire casing
 * lives only in `core:network`", #23). The offline outbox's `SetWorkingState` intent (`core:data`)
 * emits a minimal `{"status": "<token>"}` PATCH body and must use the exact hyphenated wire casing
 * (`in-progress`/`in-review`), not the domain PascalCase. Total and explicit so a new [WorkingState]
 * value forces a token decision here rather than silently shipping a wrong status. Inverse of
 * [TaskStatusWire.toWorkingState].
 */
fun WorkingState.toWireToken(): String = when (this) {
    WorkingState.Open -> "open"
    WorkingState.InProgress -> "in-progress"
    WorkingState.InReview -> "in-review"
    WorkingState.Done -> "done"
    WorkingState.Dropped -> "dropped"
}

/**
 * `DefStatus` → [DefinitionState]. [DefStatusWire.Unknown] degrades to [DefinitionState.Active] so a
 * recurring definition with an additive status keeps firing rather than silently switching off.
 */
fun DefStatusWire.toDefinitionState(): DefinitionState = when (this) {
    DefStatusWire.Active -> DefinitionState.Active
    DefStatusWire.InReview -> DefinitionState.InReview
    DefStatusWire.Archived -> DefinitionState.Archived
    DefStatusWire.Unknown -> DefinitionState.Active
}

/**
 * [DefinitionState] → its wire `DefStatus` token — the **write** direction (ADR-0011, #299), the
 * recurring-definition mirror of [WorkingState.toWireToken]. The offline outbox's `SetDefinitionState`
 * intent (`core:data`) emits a minimal `{"status": "<token>"}` PATCH body and must use the exact wire
 * casing the read mapper round-trips on — `active`/`in-review`/`archived` (the [DefStatusWire]
 * `@SerialName`s). Total and explicit so a new [DefinitionState] value forces a token decision here.
 * Inverse of [DefStatusWire.toDefinitionState].
 */
fun DefinitionState.toWireToken(): String = when (this) {
    DefinitionState.Active -> "active"
    DefinitionState.InReview -> "in-review"
    DefinitionState.Archived -> "archived"
}

/**
 * `Priority` → domain [Priority] (#375). [PriorityWire.Unknown] degrades to [Priority.Default] —
 * which here is not merely a safe fallback but the server's own contract: `priority` is
 * `#[serde(default)]`, so an absent field and an additive token both mean "the normal bucket". An
 * item can therefore never rank as more or less urgent than it is because of a token we don't know.
 */
fun PriorityWire.toPriority(): Priority = when (this) {
    PriorityWire.Fire -> Priority.Fire
    PriorityWire.Normal -> Priority.Normal
    PriorityWire.Backlog -> Priority.Backlog
    PriorityWire.Unknown -> Priority.Default
}

/**
 * [Priority] → its wire token — the **write** direction (ADR-0011 "the wire casing lives only in
 * `core:network`", #375). The offline outbox's `SetPriority` intent (`core:data`) emits a minimal
 * `{"priority": "<token>"}` PATCH body and must use the exact lowercase casing the read mapper
 * round-trips on. Total and explicit so a new [Priority] value forces a token decision here rather
 * than silently shipping a wrong bucket. Inverse of [PriorityWire.toPriority].
 */
fun Priority.toWireToken(): String = when (this) {
    Priority.Fire -> "fire"
    Priority.Normal -> "normal"
    Priority.Backlog -> "backlog"
}

// There are deliberately **no wire → [OccurrenceState] mappers** here (#390, ADR-0053 decision 4).
// `OccurrenceStatusWire.toOccurrenceState` and `DerivedChoreOccurrenceStatusWire.toOccurrenceState`
// lived here until this slice and lost their last production caller when the read stack moved onto
// facts; they were removed rather than left as dead public API, because what they offered is precisely
// the shortcut the ADR forbids. An [OccurrenceState] is a *reading*: it is a function of the stored
// fact, the parent's [DefinitionState], `OccurrenceCoverage` and today. Three of those four are not on
// the wire, so a mapper from a wire token alone can only guess — and it guessed exactly the two ways
// the ADR calls out, degrading an unreadable token to `Scheduled` and passing the server's UTC-derived
// `missed` through as if it were an answer about the user's local today.
//
// A reading is produced in one place: `resolveOccurrenceState` in `core:model`, from an
// `OccurrenceFact` this file's `toResolution` / `toResolutionOrNull` built. The wire condenses to
// facts here; the reading is derived there.

/**
 * `OccurrenceStatus` → [OccurrenceResolution] — the **fact** condensation for an event firing (#390,
 * ADR-0053 decision 4). Distinct from an [OccurrenceState], which is a render-time *reading* and is
 * derived in `core:model`, never mapped from a wire token here: this produces the half that is
 * genuinely stored and may therefore be written to a row.
 *
 * **Total, and deliberately never `null`** — unlike its chore sibling
 * [DerivedChoreOccurrenceStatusWire.toResolutionOrNull]. `GET /events/{id}/occurrences` returns only
 * *stored* rows (`backend/src/handlers/event_occurrences.rs:57-60`), so every element it yields is a
 * fact, including one reading `scheduled`: for an event that is a written row recording no progress,
 * not the derived "nothing has happened yet". `dropped` is the event spelling of the same terminal the
 * chore endpoint spells `skipped`; both condense to [OccurrenceResolution.Skipped].
 *
 * [OccurrenceStatusWire.Unknown] degrades to [OccurrenceResolution.Scheduled] — the row exists, we
 * simply cannot read its additive token, and recording that as a fact keeps the date from later being
 * *derived* as Missed out of our own ignorance (ADR-0053).
 */
fun OccurrenceStatusWire.toResolution(): OccurrenceResolution = when (this) {
    OccurrenceStatusWire.Scheduled -> OccurrenceResolution.Scheduled
    OccurrenceStatusWire.InProgress -> OccurrenceResolution.InProgress
    OccurrenceStatusWire.DoneOnTime -> OccurrenceResolution.DoneOnTime
    OccurrenceStatusWire.DoneLate -> OccurrenceResolution.DoneLate
    OccurrenceStatusWire.Dropped -> OccurrenceResolution.Skipped
    OccurrenceStatusWire.Unknown -> OccurrenceResolution.Scheduled
}

/**
 * `DerivedChoreOccurrenceStatus` → [OccurrenceResolution], **or `null` for the two arms that are not
 * facts at all** (#390, ADR-0053 decision 4). `GET /chores/{id}/occurrences` returns a derived *view*
 * over the requested window, mixing stored rows with the server's opinion about dates that have no
 * row:
 *
 * - `scheduled` means "no record exists for this date". Storing it as an
 *   [OccurrenceResolution.Scheduled] fact would assert the opposite — that the server holds a row —
 *   and would then suppress the client's own Scheduled-vs-Missed derivation forever.
 * - `missed` is a reading, not a record, and it is the server's reading against
 *   `Utc::now().date_naive()` (`backend/src/handlers/occurrences.rs:465`, `:488-495`) while the
 *   `complete_by` in the same arm honours the user's zone. The client derives Missed itself, against
 *   the user's **local** today. Persisting the server's answer would bake a UTC-vs-local off-by-a-day
 *   into the cache — the exact defect ADR-0053 was written about.
 *
 * For both, the caller writes **no row**: absence inside synced coverage is the honest record, and the
 * reading is recomputed at render time. The remaining four arms are the stored `ChoreOccurrenceStatus`
 * partition 1:1 — note `skipped`, never `dropped` (that spelling survives only as a serde alias for
 * rows the v0.1 backend wrote, and is what the *event* endpoint emits instead).
 *
 * [DerivedChoreOccurrenceStatusWire.Unknown] degrades to an [OccurrenceResolution.Scheduled] **fact**
 * rather than to `null`: an additive token is by definition neither of the two derived arms we know,
 * so a row exists; recording it keeps the date from being derived as Missed out of ignorance, which
 * `null` here would actively cause.
 */
fun DerivedChoreOccurrenceStatusWire.toResolutionOrNull(): OccurrenceResolution? = when (this) {
    DerivedChoreOccurrenceStatusWire.Scheduled -> null
    DerivedChoreOccurrenceStatusWire.Missed -> null
    DerivedChoreOccurrenceStatusWire.InProgress -> OccurrenceResolution.InProgress
    DerivedChoreOccurrenceStatusWire.DoneOnTime -> OccurrenceResolution.DoneOnTime
    DerivedChoreOccurrenceStatusWire.DoneLate -> OccurrenceResolution.DoneLate
    DerivedChoreOccurrenceStatusWire.Skipped -> OccurrenceResolution.Skipped
    DerivedChoreOccurrenceStatusWire.Unknown -> OccurrenceResolution.Scheduled
}

/**
 * Which recurring kind an [OccurrenceAction] is being written against (ADR-0011 read/write
 * asymmetry). Selects the kind-appropriate wire token for [OccurrenceAction.Skip]: a chore is
 * `skipped`, an event is `dropped`.
 */
enum class OccurrenceKind { Chore, Event }

/**
 * The write-mapper for the coarse domain [OccurrenceAction] → its wire token (ADR-0011,
 * CONTRACT-NOTES → read/write asymmetry). The client only ever *sets* `in_progress`/`done`/
 * `skipped`|`dropped`; the finer read states (`scheduled`, `missed`, the on-time/late split) are
 * server-derived and never written. [OccurrenceAction.Skip] diverges by [kind]: chore → `skipped`,
 * event → `dropped`.
 */
fun OccurrenceAction.toWireToken(kind: OccurrenceKind): String = when (this) {
    OccurrenceAction.Start -> "in_progress"
    OccurrenceAction.Complete -> "done"
    OccurrenceAction.Skip -> when (kind) {
        OccurrenceKind.Chore -> "skipped"
        OccurrenceKind.Event -> "dropped"
    }
}

/**
 * The **optimistic** [WorkingState] a coarse [OccurrenceAction] sets on a cached calendar row (#74).
 * The Calendar feed reports progress on the Task axis ([WorkingState]), so acting on a firing applies
 * the equivalent state instantly before the kind-scoped occurrence write replays + reconciles: Start →
 * In-progress, Complete → Done, Skip → Dropped (the gentle, non-`missed` axis — design-principle #4).
 */
fun OccurrenceAction.toWorkingState(): WorkingState = when (this) {
    OccurrenceAction.Start -> WorkingState.InProgress
    OccurrenceAction.Complete -> WorkingState.Done
    OccurrenceAction.Skip -> WorkingState.Dropped
}

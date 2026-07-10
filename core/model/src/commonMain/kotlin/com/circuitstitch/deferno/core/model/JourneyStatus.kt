package com.circuitstitch.deferno.core.model

/**
 * The read-only **journey status** indicator (ADR-0044) — a *reading* over a [Task]'s [WorkingState]
 * plus the orthogonal, server-derived [Task.blocked] flag (CONTEXT.md → "Working state" /
 * "Blocked / blocker"). It is display-only: the interactive state editor still names the five
 * canonical [WorkingState]s; this vocabulary lives ONLY in the indicator and is never a state name.
 *
 * The reading returns typed codes only — the View owns [JourneyLabel] → localized string and
 * [slot]/[style] → visual. Compose-free and iOS-safe, so the same reading powers the Compose
 * indicator, the SwiftUI indicator (via the Apple bridge), and the item-tree de-emphasis.
 */

/** The three-slot track position — initial `TO-DO`, a middle marker, or the terminal `DONE`. */
enum class JourneySlot { Initial, Middle, Terminal }

/** How the slot renders: normal, the blocked (error) treatment, or the shelved dashed/struck tail. */
enum class JourneyStyle { Normal, Blocked, NotDoing }

/** The display-only journey vocabulary the View maps to a `tasks_journey_*` string. */
enum class JourneyLabel { ToDo, InProgress, InReview, Done, NotDoing, Blocked }

/** The whole reading: where on the 3-slot track, which label, and the visual style. */
data class JourneyStatus(val slot: JourneySlot, val label: JourneyLabel, val style: JourneyStyle)

/**
 * The journey-status reading over [state] + [blocked].
 *
 * `blocked` overrides **only non-terminal** states (Open/InProgress/InReview) and forces
 * [JourneySlot.Middle] ("started-but-stuck"). **Terminal (Done) and shelved (Dropped) ignore
 * `blocked`** — a finished or shelved item is never rendered BLOCKED.
 */
fun journeyStatus(state: WorkingState, blocked: Boolean): JourneyStatus = when (state) {
    WorkingState.Done -> JourneyStatus(JourneySlot.Terminal, JourneyLabel.Done, JourneyStyle.Normal)
    WorkingState.Dropped -> JourneyStatus(JourneySlot.Middle, JourneyLabel.NotDoing, JourneyStyle.NotDoing)
    WorkingState.Open ->
        if (blocked) JourneyStatus(JourneySlot.Middle, JourneyLabel.Blocked, JourneyStyle.Blocked)
        else JourneyStatus(JourneySlot.Initial, JourneyLabel.ToDo, JourneyStyle.Normal)
    WorkingState.InProgress ->
        if (blocked) JourneyStatus(JourneySlot.Middle, JourneyLabel.Blocked, JourneyStyle.Blocked)
        else JourneyStatus(JourneySlot.Middle, JourneyLabel.InProgress, JourneyStyle.Normal)
    WorkingState.InReview ->
        if (blocked) JourneyStatus(JourneySlot.Middle, JourneyLabel.Blocked, JourneyStyle.Blocked)
        else JourneyStatus(JourneySlot.Middle, JourneyLabel.InReview, JourneyStyle.Normal)
}

/** The journey-status reading for this Task — [journeyStatus] over its [Task.workingState] + [Task.blocked]. */
fun Task.journeyStatus(): JourneyStatus = journeyStatus(workingState, blocked)

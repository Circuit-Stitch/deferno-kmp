package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto

/**
 * A platform-neutral, **jargon-free capture input** (ADR-0036): the behavioral schema an off-Deferno
 * caller — Google Assistant / Gemini, Siri / App Intents, an MCP agent — fills from world knowledge to
 * capture a new item **without ever naming Deferno's [ItemKind]s**. The caller does the speech
 * recognition + NLU off-Deferno; [deriveCreatePayload] applies the ADR-0036 decision tree to pick the
 * kind and build the kind-specific [CreateItem.Payload]. This is the read/categorize half of the
 * "Deferno runs no inference on this path" decision — the derivation is **deterministic** (no
 * [com.circuitstitch.deferno.core.agent] call), so it never crosses ADR-0027's propose-only boundary.
 *
 * Three orthogonal discriminators, answered from obligation rather than taxonomy:
 *  - [occursAtSetTime] — does it happen at a set time you attend? → **Event**. It **wins over**
 *    [repeats]: a recurring stand-up is still an Event (the ADR-0036 edge case "recurring +
 *    occurs-at-set-time → Event").
 *  - else [repeats] — `false` → a one-off **Task** (the ADR-0036 edge case "one-off must-do → Task").
 *  - else [ifMissed] — when a recurring firing's time passes undone, does the obligation **carry
 *    forward** (→ **Chore**) or **lapse** (→ **Habit**)? The fuzzy Habit-vs-Chore split reduced to a
 *    universal obligation-vs-aspiration judgment a general assistant can make.
 *
 * The operands map straight onto the existing create wire DTOs — no new write plumbing — and the
 * single [date] (`complete_by`) carries an Event's set day or any other kind's deadline.
 *
 * This is a **versioned public surface** external assistants bind to, **and a cross-repo shared
 * contract** with `defernowork-mcp`'s `capture_item` tool (ADR-0036 amendment): the tree below is the
 * spec, and the two implementations must stay in **lockstep** — drift is a correctness bug. The backend
 * remains the authority for the kind semantics the tree encodes (#231).
 *
 * Defined and tested once in the core, like [CommandKind].
 */
data class CaptureInput(
    val title: String,
    /** Q1: occurs at a set time you attend? `true` → Event (wins over [repeats]). */
    val occursAtSetTime: Boolean,
    /** Q2: does it repeat? Only consulted when not [occursAtSetTime]. */
    val repeats: Boolean,
    /** Q3: when a recurring firing passes undone, does it carry forward or lapse? Required when [repeats] (and not [occursAtSetTime]). */
    val ifMissed: IfMissed? = null,
    val description: String? = null,
    /** ISO-8601 date (`YYYY-MM-DD`) → every kind's `complete_by` (an Event's set day; else a deadline). Required for an Event. */
    val date: String? = null,
    /** Clock time (`HH:MM`) → the derived kind's deadline / start time-of-day. */
    val timeOfDay: String? = null,
    /** The cadence for a recurring kind. Required when [repeats]; optional for a recurring Event. */
    val recurrence: RecurrenceDto? = null,
)

/** Q3 of the ADR-0036 tree: when a recurring firing's time passes undone, does the obligation carry forward or lapse? */
enum class IfMissed {
    /** The undone firing rolls forward — an obligation you still owe (→ [ItemKind.Chore]). */
    CarriesForward,

    /** The undone firing is simply gone — an aspiration, not a debt (→ [ItemKind.Habit]). */
    Lapses,
}

/**
 * Apply the ADR-0036 decision tree to derive the kind-specific [CreateItem.Payload]. Deterministic and
 * inference-free (the caller already categorized); the kind is read straight off the behavioral
 * discriminators, never inferred from the operands (ADR-0015 — no field-inference).
 *
 * An external caller fills this, so it is a **trust boundary**: each branch validates the operands its
 * kind requires — a non-blank [title] always; a [date] for an Event's set time; a [recurrence] **and**
 * [ifMissed] for a repeating capture. A malformed input throws [IllegalArgumentException] for the
 * binding layer to surface — never a silently-wrong kind or a dropped capture.
 */
fun CaptureInput.deriveCreatePayload(): CreateItem.Payload {
    require(title.isNotBlank()) { "capture requires a non-blank title" }
    return when {
        // Q1 — occurs at a set time you attend → Event (wins over repeats; an Event carries its own
        // optional recurrence, so a recurring stand-up stays an Event).
        occursAtSetTime -> CreateItem.Payload.Event(
            CreateEventPayload(
                title = title,
                completeBy = requireNotNull(date) { "an Event capture (occursAtSetTime) requires a date" },
                startTimeOfDay = timeOfDay,
                recurrence = recurrence,
                description = description,
            ),
        )
        // Q2 — does not repeat → one-off Task.
        !repeats -> CreateItem.Payload.Task(
            CreateTaskPayload(
                title = title,
                description = description,
                completeBy = date,
                deadlineTimeOfDay = timeOfDay,
            ),
        )
        // Q3 — repeats: carries-forward → Chore, lapses → Habit (both require a recurrence cadence).
        else -> {
            val cadence = requireNotNull(recurrence) { "a repeating capture requires a recurrence" }
            when (
                requireNotNull(ifMissed) {
                    "a repeating capture requires ifMissed (carries-forward → Chore, lapses → Habit)"
                }
            ) {
                IfMissed.CarriesForward -> CreateItem.Payload.Chore(
                    CreateChorePayload(
                        title = title,
                        recurrence = cadence,
                        description = description,
                        completeBy = date,
                        deadlineTimeOfDay = timeOfDay,
                    ),
                )
                IfMissed.Lapses -> CreateItem.Payload.Habit(
                    CreateHabitPayload(
                        title = title,
                        recurrence = cadence,
                        description = description,
                        completeBy = date,
                        deadlineTimeOfDay = timeOfDay,
                    ),
                )
            }
        }
    }
}

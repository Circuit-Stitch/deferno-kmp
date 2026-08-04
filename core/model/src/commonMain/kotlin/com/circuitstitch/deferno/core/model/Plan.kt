package com.circuitstitch.deferno.core.model

/**
 * One slot of a daily plan, as the *ordering* alone knows it (#21, #385): the planned item's id and
 * which kind it is. The plan is a curation the server owns (ADR-0053) — it holds positions, never
 * payload — so this is the whole of what a plan row is before it is resolved against the per-kind
 * caches.
 *
 * **The id is a raw UUID string, deliberately.** A plan spans all four kinds, exactly like the
 * cross-kind [Item] projection it resolves into, and the four `@JvmInline` id types ([TaskId],
 * [HabitId], [ChoreId], [EventId]) are mutually incompatible — a list that may hold any of them can
 * only be typed at their common denominator. [kind] carries the discrimination instead, so the
 * resolve dispatches to the right store rather than probing all four.
 *
 * [kind] is nullable because it is a *server token*: `/items/plan` tags every row, but a token this
 * client does not recognise must not be silently coerced. Defaulting an unknown kind to [ItemKind.Task]
 * is precisely the defect #385 exists to fix — it is how a Habit came to vanish from the plan in the
 * first place — so an unrecognised kind stays `null` and the row is handled as unresolvable rather
 * than mis-resolved.
 */
data class PlanItemRef(
    val id: String,
    val kind: ItemKind?,
)

/**
 * One resolved row of the daily Plan (#385) — the plan's ordering joined back to the cached item.
 *
 * **Why this is a pair and not just an [Item].** [item] is the cross-kind projection every row has,
 * and it is what makes a Habit/Chore/Event renderable at all. But four shipped Plan affordances read
 * fields that exist only on a [Task] — the ✦ suggestion and its choice card (#375, via `priority` and
 * `pinned`), the deadline subline (`completeBy`/`deadlineTimeOfDay`), the attention footer
 * (`workingState`) — and [Item] deliberately projects none of them (see its KDoc on why a Task's
 * deadline is not carried there). Rendering the plan from [Item] alone would have made this issue's
 * fix a silent regression of those four.
 *
 * So [task] is the concrete row, present **exactly when** [item]'s kind is [ItemKind.Task] and absent
 * for the three recurring kinds. A consumer reads [item] for anything cross-kind (title, kind badge,
 * blocked state, the recurrence pair) and reaches for [task] only where the affordance is genuinely
 * Task-shaped — where `null` is the honest answer for a recurring row, not a missing value to
 * substitute a default for.
 *
 * **No occurrence state lives here.** A recurring row's done/scheduled state is a *reading* against
 * today, not a stored fact (ADR-0053), and the fact table it will be derived from does not exist yet
 * (#390). Half A of #385 therefore renders a recurring row with no completion state at all rather
 * than an unchecked control asserting "not done" on no evidence.
 */
data class PlanRow(
    val item: Item,
    val task: Task? = null,
)

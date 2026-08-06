package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.cadenceModeFromWire
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.EventDetailDto
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.MonthlyAnchorDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import com.circuitstitch.deferno.core.network.dto.RecurrenceEndDto
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The DTO→domain mapping for the recurring kinds — Habit / Chore / Event (ADR-0011 "condense at the
 * edge", #71), the sibling of `TaskMapper.kt`. The wire ugliness (string ids/timestamps, the
 * overloaded `DefStatus`, the loosely-typed `recurrence`) stays in `core:network`; everything above
 * sees only the clean `core:model` definitions, each governed by [com.circuitstitch.deferno.core.model.DefinitionState]
 * (the "light switch") — never confused with a [com.circuitstitch.deferno.core.model.WorkingState].
 *
 * The full single-item DTOs ([HabitDetailDto]/…) and the `/items` [ItemView] variants share the same
 * fields, so each maps to a [HydrationState.Full] domain row; the heterogeneous-`/items` extractors
 * ([asHabitOrNull]/[asChoreOrNull]/[asEventOrNull]) return `null` for the non-matching kinds.
 */

/**
 * The wire `recurrence` object → domain [Recurrence]. **This is where the rule used to be destroyed**
 * (#382): the mapper condensed the flat wire object down to `frequency` + `days` and dropped
 * `every_n_days.n`, `monthly.interval`/`.on`, `yearly.interval`/`.month`/`.day`, `custom.rrule` and the
 * whole `end` bound on the floor — at the **network** boundary, before anything was cached, so the DB
 * was only ever a faithful mirror of an already-lossy domain.
 *
 * This is one of only two places a [Recurrence] is ever built (the other is the row codec in
 * `core:data`), which is what lets the domain be a sealed [Cadence] while the DTO stays a flat,
 * all-defaulted bag — see [Cadence]. `null` DTO → `null` domain (a non-recurring item carries no rule).
 */
fun RecurrenceDto?.toDomain(): Recurrence? = this?.let { dto ->
    Recurrence(cadence = dto.toCadence(), bound = dto.end.toDomain())
}

/**
 * The wire `type` token plus the parameters hoisted beside it → the one [Cadence] variant that owns
 * them. Every arm is tolerant (ADR-0005): the parameters arrive as independent nullable fields, so a
 * cadence naming itself `monthly` with no `interval` is a shape this reader has to accept, and an
 * over-strict read here would resurrect the whole-snapshot decode stall of #381.
 *
 * The chosen defaults are readings, not guesses. An absent multiplier means "every one of them" — `1`
 * is the wire's own default for `interval`/`n`, so `?: 1` restores what the sender omitted rather than
 * inventing a cycle. An absent `custom.rrule` leaves nothing to preserve, hence `""`. An unmodelled or
 * missing `type` is the only genuinely lossy arm, and [Cadence.Unmodelled] keeps the token itself so
 * the rule survives a cache/backup round-trip under its own name (#382).
 */
private fun RecurrenceDto.toCadence(): Cadence = when (type) {
    "daily" -> Cadence.Daily
    "every_n_days" -> Cadence.EveryNDays(n ?: 1)
    "weekly" -> Cadence.Weekly(days)
    "monthly" -> Cadence.Monthly(interval ?: 1, on.toDomain())
    "yearly" -> Cadence.Yearly(interval ?: 1, month ?: 1, day ?: 1)
    "custom" -> Cadence.Custom(rrule ?: "")
    else -> Cadence.Unmodelled(type ?: "")
}

/**
 * The nested wire `recurrence.end` → domain [RecurrenceBound]. An **absent** `end` is the never bound
 * (the only encoding the server emits — its `Serialize` skips the key), and an explicit
 * `{"type":"never"}` is tolerated because the backend's `Deserialize` accepts one. Anything unparseable
 * — an unknown token, an `on_date` with no/garbled `date`, an `after_count` with no `n` — degrades to
 * [RecurrenceBound.Never] rather than throwing: an over-strict bound would resurrect exactly the
 * whole-snapshot decode failure of #381.
 */
private fun RecurrenceEndDto?.toDomain(): RecurrenceBound = when (this?.type) {
    "on_date" -> date.toLocalDateOrNull()?.let(RecurrenceBound::OnDate) ?: RecurrenceBound.Never
    "after_count" -> n?.let(RecurrenceBound::AfterCount) ?: RecurrenceBound.Never
    else -> RecurrenceBound.Never
}

/**
 * The nested wire `recurrence.on` → domain [MonthlyAnchor]; an unknown token or a half-populated anchor
 * degrades to `null` (the monthly rule is still usable, it just cannot say which day).
 */
private fun MonthlyAnchorDto?.toDomain(): MonthlyAnchor? = when (this?.type) {
    "day_of_month" -> day?.let(MonthlyAnchor::DayOfMonth)
    "nth_weekday" -> if (nth != null && weekday != null) MonthlyAnchor.NthWeekday(nth, weekday) else null
    else -> null
}

/** Parses the `on_date` bound's ISO-8601 date, or `null` when absent/unparseable (defensive). */
private fun String?.toLocalDateOrNull(): LocalDate? =
    this?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// --- Habit ---

fun HabitDetailDto.toDomain(): Habit = Habit(
    id = HabitId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    targetDate = targetDate.toInstantOrNull(),
    priority = priority.toPriority(),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    series = series.toDomain(),
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Habit] from the `habit` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asHabitOrNull(): Habit? = (this as? ItemView.Habit)?.let { v ->
    Habit(
        id = HabitId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        completeBy = v.completeBy.toInstantOrNull(),
        deadlineTimeOfDay = v.deadlineTimeOfDay.toLocalTimeOrNull(),
        targetDate = v.targetDate.toInstantOrNull(),
        priority = v.priority.toPriority(),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        series = v.series.toDomain(),
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

// --- Chore ---

/**
 * The wire `cadence_mode` token is typed **here**, in the mapper, and the DTO field deliberately stays a
 * plain `String?`. Making it a `@Serializable enum` would look tidier and would silently destroy the
 * thing [com.circuitstitch.deferno.core.model.CadenceMode.Unmodelled] exists to preserve: `DefernoJson`
 * sets `coerceInputValues = true` (ADR-0005), which rewrites an unrecognised enum token to the
 * property's default before any mapper is reached — the unknown mode would arrive already flattened to
 * `rolling`. Same asymmetry, same reason, as the flat [RecurrenceDto] behind the sealed [Cadence].
 */
fun ChoreDetailDto.toDomain(): Chore = Chore(
    id = ChoreId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    cadenceMode = cadenceModeFromWire(cadenceMode),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    completeBy = completeBy.toInstantOrNull(),
    deadlineTimeOfDay = deadlineTimeOfDay.toLocalTimeOrNull(),
    targetDate = targetDate.toInstantOrNull(),
    priority = priority.toPriority(),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    series = series.toDomain(),
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Chore] from the `chore` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asChoreOrNull(): Chore? = (this as? ItemView.Chore)?.let { v ->
    Chore(
        id = ChoreId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        cadenceMode = cadenceModeFromWire(v.cadenceMode),
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        completeBy = v.completeBy.toInstantOrNull(),
        deadlineTimeOfDay = v.deadlineTimeOfDay.toLocalTimeOrNull(),
        targetDate = v.targetDate.toInstantOrNull(),
        priority = v.priority.toPriority(),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        series = v.series.toDomain(),
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

// --- Event ---

fun EventDetailDto.toDomain(): Event = Event(
    id = EventId(id),
    orgSlug = orgSlug,
    title = title,
    definitionState = status.toDefinitionState(),
    recurrence = recurrence.toDomain(),
    allDay = allDay,
    completeBy = completeBy.toInstantOrNull(),
    endTime = endTime.toInstantOrNull(),
    startTimeOfDay = startTimeOfDay.toLocalTimeOrNull(),
    endTimeOfDay = endTimeOfDay.toLocalTimeOrNull(),
    labels = labels,
    parentId = parentId?.let(::TaskId),
    targetDate = targetDate.toInstantOrNull(),
    priority = priority.toPriority(),
    pinned = pinned,
    sequence = sequence,
    ref = ref,
    dateCreated = Instant.parse(dateCreated),
    deletedAt = deletedAt.toInstantOrNull(),
    hydration = HydrationState.Full,
    ownerOrgId = ownerOrgId?.let(::OrgId),
    description = description,
    seriesId = seriesId,
    series = series.toDomain(),
    blocked = blocked,
    isBlocker = isBlocker,
)

/** Extracts a domain [Event] from the `event` variant of an [ItemView]; `null` for the other kinds. */
fun ItemView.asEventOrNull(): Event? = (this as? ItemView.Event)?.let { v ->
    Event(
        id = EventId(v.id),
        orgSlug = v.orgSlug,
        title = v.title,
        definitionState = v.status.toDefinitionState(),
        recurrence = v.recurrence.toDomain(),
        allDay = v.allDay,
        completeBy = v.completeBy.toInstantOrNull(),
        endTime = v.endTime.toInstantOrNull(),
        startTimeOfDay = v.startTimeOfDay.toLocalTimeOrNull(),
        endTimeOfDay = v.endTimeOfDay.toLocalTimeOrNull(),
        labels = v.labels,
        parentId = v.parentId?.let(::TaskId),
        targetDate = v.targetDate.toInstantOrNull(),
        priority = v.priority.toPriority(),
        pinned = v.pinned,
        sequence = v.sequence,
        ref = v.ref,
        dateCreated = Instant.parse(v.dateCreated),
        deletedAt = v.deletedAt.toInstantOrNull(),
        hydration = HydrationState.Full,
        ownerOrgId = v.ownerOrgId?.let(::OrgId),
        description = v.description,
        seriesId = v.seriesId,
        series = v.series.toDomain(),
        blocked = v.blocked,
        isBlocker = v.isBlocker,
    )
}

/** Parses an RFC3339 timestamp string to an [Instant], or `null` when absent (mirrors TaskMapper). */
private fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)

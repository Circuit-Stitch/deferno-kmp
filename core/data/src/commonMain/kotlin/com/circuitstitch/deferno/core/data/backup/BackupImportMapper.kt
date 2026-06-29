package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto

/**
 * The **inbound** wire→create-payload mapping for on-device import (#314, ADR-0041): turns a read-shape
 * [ItemView] from a Backup `manifest.json` into the matching `POST /{kind}` create payload the offline
 * outbox replays under the item's **original id** (ADR-0034). It's the import twin of the export-side
 * `BackupItemMapper` (domain→[ItemView]); the local optimistic upsert reuses `core:network`'s read
 * mappers (`asTaskOrNull()` …) for full fidelity, while *this* maps only the subset the v0.1 create
 * endpoints accept — server-derived state (status, timestamps, completion) is set later, not on create.
 *
 * Re-homing falls out for free: the create payloads carry **no `owner_org_id`**, so the server homes the
 * restored item in the active account's personal org regardless of the file's `owner_org_id` (ADR-0041).
 */
internal fun ItemView.Task.toCreatePayload(): CreateTaskPayload = CreateTaskPayload(
    title = title,
    description = description,
    completeBy = completeBy,
    deadlineTimeOfDay = deadlineTimeOfDay,
    labels = labels.ifEmpty { null },
    parentId = parentId,
    productive = productive,
    desire = desire,
)

internal fun ItemView.Habit.toCreatePayload(): CreateHabitPayload = CreateHabitPayload(
    title = title,
    recurrence = recurrence ?: RecurrenceDto(), // a habit needs a cadence; an absent one restores as "unknown"
    description = description,
    completeBy = completeBy,
    deadlineTimeOfDay = deadlineTimeOfDay,
    labels = labels.ifEmpty { null },
)

internal fun ItemView.Chore.toCreatePayload(): CreateChorePayload = CreateChorePayload(
    title = title,
    recurrence = recurrence ?: RecurrenceDto(),
    cadenceMode = cadenceMode,
    description = description,
    completeBy = completeBy,
    deadlineTimeOfDay = deadlineTimeOfDay,
    labels = labels.ifEmpty { null },
)

internal fun ItemView.Event.toCreatePayload(): CreateEventPayload = CreateEventPayload(
    title = title,
    completeBy = completeBy ?: dateCreated, // the start day is required on create; fall back to creation time
    endTime = endTime,
    startTimeOfDay = startTimeOfDay,
    endTimeOfDay = endTimeOfDay,
    recurrence = recurrence,
    description = description,
    labels = labels.ifEmpty { null },
)

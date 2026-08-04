package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.datetime.LocalDate

/**
 * The daily-plan **write** seam the UI/feature layer drives (ADR-0001, #23) — the plan counterpart to
 * [com.circuitstitch.deferno.core.data.task.TaskWriter]. Each call applies optimistically to the
 * cached day ordering and enqueues an intent-based, idempotent plan mutation for FIFO replay when
 * online. See [OutboxPlanWriter] and the `PlanAdd`/`PlanRemove`/`PlanReorder` intents.
 *
 * Kind-neutral since #385: a plan holds items of any kind. [add] and [reorder] take a [PlanItemRef]
 * because the kind has to be recorded alongside the id for the optimistic local write to be
 * resolvable; [remove] takes a bare id because deleting a slot needs no kind to find it.
 */
interface PlanWriter {

    /** Add [ref] to the `(date, tz)` plan (`POST items/plan/add`); a no-op locally if already present. */
    suspend fun add(ref: PlanItemRef, date: LocalDate, tz: String)

    /** Remove [itemId] from the `(date, tz)` plan (`POST items/plan/remove`). */
    suspend fun remove(itemId: String, date: LocalDate, tz: String)

    /** Set the `(date, tz)` plan to exactly [refs] in order (`POST items/plan/reorder`). */
    suspend fun reorder(refs: List<PlanItemRef>, date: LocalDate, tz: String)
}

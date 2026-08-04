package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The local source-of-truth port for daily-plan *ordering* (ADR-0001, #22, #385). A plan row holds
 * only the `(date, position) -> (itemId, kind)` ordering (#21); the referenced items live in the four
 * per-kind caches and are reconciled independently. So this store deals purely in ordered
 * [PlanItemRef] lists — the [PlanRepository] joins them back to domain rows.
 *
 * **The day is keyed by date alone; `tz` is carried but not part of the identity (#385).** The server
 * has one plan per day and uses the zone only to resolve *which* day, so a per-zone local key
 * manufactured days the server cannot represent and made a zone flip blank the cached plan. The zone
 * still rides [replacePlan] because it is worth recording which zone a day was captured under.
 *
 * Like [com.circuitstitch.deferno.core.data.task.TaskLocalStore], the port keeps the plan reconcile
 * (a per-day full-snapshot replace) unit-testable against an in-memory fake while the SQLDelight
 * impl proves the real SQL path.
 */
interface PlanLocalStore {

    /** The ordered [PlanItemRef]s for [date], observed as a DB `Flow`; re-emits on a replace. */
    fun observePlan(date: LocalDate): Flow<List<PlanItemRef>>

    /**
     * A one-shot snapshot of the ordered [PlanItemRef]s for [date] — the non-`Flow` read the offline
     * write path needs (#23): an optimistic plan mutation (add/remove/reorder) reads the current
     * order, transforms it, and [replacePlan]s the result. Distinct from [observePlan] (the reactive
     * UI stream) the way [com.circuitstitch.deferno.core.data.task.TaskLocalStore.get] is distinct
     * from `observe`. A point-in-time read: a concurrent [replacePlan] may make the snapshot stale,
     * but the post-flush reconcile (ADR-0001 LWW) corrects any divergence.
     */
    suspend fun currentPlan(date: LocalDate): List<PlanItemRef>

    /**
     * Replaces the whole day's plan with [refs] in order — the per-day full-snapshot reconcile
     * (ADR-0001): delete the day's existing entries then re-insert the fresh ordered set, atomically,
     * so the day never reads as half-reconciled. [tz] is stamped on each row as the zone the snapshot
     * was captured under; it does not scope the delete.
     */
    suspend fun replacePlan(date: LocalDate, tz: String, refs: List<PlanItemRef>)

    /**
     * Re-points every plan slot referencing [from] to [to] across all days (#185, id-heal): when an
     * offline-created item's client id is replaced by a different server canonical id, plan rows that
     * already pointed at it follow. Kind-neutral since #385 — a Habit/Chore/Event can be planned too,
     * so this is no longer reachable only from the Task heal path. A no-op when [from] is planned
     * nowhere.
     */
    suspend fun rekeyItem(from: String, to: String)
}

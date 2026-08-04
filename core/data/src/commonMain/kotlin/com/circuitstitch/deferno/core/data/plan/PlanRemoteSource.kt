package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.datetime.LocalDate

/**
 * The network port the plan repository refreshes through (ADR-0001, #22, #385). `/items/plan` is a
 * flat, ordered, **kind-tagged** list of item rows (CONTRACT-NOTES -> Items); the plan only needs the
 * *ordering*, so this returns the [PlanItemRef]s in plan order. (The rows themselves are reconciled
 * into the four per-kind caches by the `/items` cold sync — the plan stores only the day's ordering.)
 *
 * The kind rides along rather than being re-derived: it is what lets the repository resolve each id
 * against the one cache that can hold it. Dropping it is how a Habit came to be looked up in the Task
 * cache and silently vanish (#385).
 *
 * Offline-first (ADR-0001): a failed call is [RemoteSnapshot.Unavailable] so a failed plan refresh
 * leaves the cached plan untouched, distinct from an [RemoteSnapshot.Available] *empty* day (which the
 * reconcile honours by clearing the day's ordering).
 */
interface PlanRemoteSource {

    /**
     * The ordered [PlanItemRef]s of the plan for `(date, tz)` as [RemoteSnapshot.Available] (possibly
     * empty), or [RemoteSnapshot.Unavailable] on failure (cache untouched).
     */
    suspend fun fetchPlan(date: LocalDate, tz: String): RemoteSnapshot<List<PlanItemRef>>
}

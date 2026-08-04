package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.datetime.LocalDate

/**
 * Scriptable [PlanRemoteSource] for the plan repository tests (#22, #385). A test sets [plan] to the
 * ordered kind-tagged refs the next refresh sees; [failNext] simulates the offline-first failure path
 * (a refresh that can't reach the server returns [RemoteSnapshot.Unavailable], leaving the cached plan
 * intact).
 */
class FakePlanRemoteSource(
    var plan: List<PlanItemRef> = emptyList(),
    var failNext: Boolean = false,
) : PlanRemoteSource {

    override suspend fun fetchPlan(date: LocalDate, tz: String): RemoteSnapshot<List<PlanItemRef>> {
        if (failNext) return RemoteSnapshot.Unavailable
        return RemoteSnapshot.Available(plan)
    }
}

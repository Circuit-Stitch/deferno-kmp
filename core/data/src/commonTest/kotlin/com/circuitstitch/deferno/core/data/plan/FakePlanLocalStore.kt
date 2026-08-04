package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * In-memory [PlanLocalStore] for the plan reconcile tests (#22, #385). Keyed by `date` -> ordered
 * [PlanItemRef]s, backed by a [MutableStateFlow] so [observePlan] is a real re-emitting `Flow`. The
 * per-day replace is a single map write, mirroring the SQLDelight impl's atomic
 * delete-then-reinsert (which fires its query listeners once, at commit).
 *
 * **The key is the date alone** — the zone is recorded on the day, never part of its identity (#385),
 * so the fake reproduces the schema rather than the old `(date, tz)` map that let a test pass while
 * the real store would have manufactured two plans for one day. [zoneOf] exposes what was stamped.
 */
class FakePlanLocalStore(
    initial: Map<LocalDate, List<PlanItemRef>> = emptyMap(),
) : PlanLocalStore {

    private val plans = MutableStateFlow(initial)
    private val zones = mutableMapOf<LocalDate, String>()

    /** Direct read of the backing map for assertions. */
    val all: Map<LocalDate, List<PlanItemRef>> get() = plans.value

    /** The zone the day was last [replacePlan]d under — recorded, not keyed. */
    fun zoneOf(date: LocalDate): String? = zones[date]

    override fun observePlan(date: LocalDate): Flow<List<PlanItemRef>> =
        plans.map { it[date] ?: emptyList() }

    override suspend fun currentPlan(date: LocalDate): List<PlanItemRef> =
        plans.value[date] ?: emptyList()

    override suspend fun replacePlan(date: LocalDate, tz: String, refs: List<PlanItemRef>) {
        zones[date] = tz
        plans.value = plans.value + (date to refs)
    }

    override suspend fun rekeyItem(from: String, to: String) {
        plans.value = plans.value.mapValues { (_, refs) ->
            refs.map { if (it.id == from) it.copy(id = to) else it }
        }
    }
}

/** The ordered Task refs [ids] name — the shorthand most plan fixtures want. */
fun taskRefs(vararg ids: String): List<PlanItemRef> = ids.map { PlanItemRef(it, ItemKind.Task) }

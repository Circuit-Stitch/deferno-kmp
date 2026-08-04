package com.circuitstitch.deferno.feature.plan

import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanRow
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * In-memory [PlanRepository] for component tests: a [MutableStateFlow] of the day's ordered rows the
 * tests mutate, plus a recorded `refreshPlan()` call that can write a snapshot through.
 */
class FakePlanRepository(initial: List<PlanRow> = emptyList()) : PlanRepository {
    val plan = MutableStateFlow(initial)

    var refreshCount = 0
        private set
    val refreshArgs = mutableListOf<Pair<LocalDate, String>>()

    /** Snapshot applied on the next `refreshPlan()` (a network pull writing through). */
    var refreshSnapshot: List<PlanRow>? = null

    override fun observePlan(date: LocalDate, tz: String): Flow<List<PlanRow>> = plan

    override suspend fun refreshPlan(date: LocalDate, tz: String) {
        refreshCount++
        refreshArgs += date to tz
        refreshSnapshot?.let { plan.value = it }
    }
}

private val FIXED_CREATED = Instant.parse("2026-06-01T00:00:00Z")

internal fun task(id: String, title: String = "Task $id"): Task = Task(
    id = TaskId(id),
    orgSlug = "u-test",
    title = title,
    workingState = WorkingState.Open,
    dateCreated = FIXED_CREATED,
    hydration = HydrationState.Summary,
)

/**
 * A Task plan row — `task` populated, as the repository resolves it (#385). The [Item] side is built
 * here rather than reused from `core:data`'s projection because a component test reads only the id,
 * kind and title off it; the projection's fidelity is `core:data`'s to prove.
 */
internal fun taskRow(id: String, title: String = "Task $id"): PlanRow = task(id, title).let { t ->
    PlanRow(
        item = Item(id = t.id.value, kind = ItemKind.Task, title = t.title),
        task = t,
    )
}

/** A recurring plan row — no `task`, which is what the component's Task-only affordances key on. */
internal fun recurringRow(id: String, kind: ItemKind, title: String = "$kind $id"): PlanRow =
    PlanRow(item = Item(id = id, kind = kind, title = title))

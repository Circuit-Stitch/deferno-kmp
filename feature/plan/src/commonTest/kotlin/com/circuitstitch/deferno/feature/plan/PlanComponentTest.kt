package com.circuitstitch.deferno.feature.plan

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

private val DATE = LocalDate(2026, 6, 6)
private const val TZ = "America/New_York"

private fun TestScope.planComponent(
    repo: FakePlanRepository,
    output: (PlanComponent.Output) -> Unit = {},
) = DefaultPlanComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    planRepository = repo,
    date = DATE,
    tz = TZ,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class PlanComponentTest {

    @Test
    fun stateReflectsTheDaysOrderedPlan() = runTest {
        val repo = FakePlanRepository()
        val component = planComponent(repo)

        component.state.test {
            assertEquals(emptyList(), awaitItem().rows)
            repo.plan.value = listOf(taskRow("a"), taskRow("b"))
            assertEquals(listOf("a", "b"), awaitItem().rows.map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stateCarriesRecurringRowsAlongsideTasks() = runTest {
        // #385: the day is a cross-kind curation. A Habit or Chore the server seeded reaches the
        // component as a row of its own kind, with no `task` — it used to be dropped before this point.
        val repo = FakePlanRepository()
        val component = planComponent(repo)

        component.state.test {
            assertEquals(emptyList(), awaitItem().rows)
            repo.plan.value = listOf(
                recurringRow("h", ItemKind.Habit, "Take a Walk"),
                taskRow("t", "Call the plumber"),
                recurringRow("c", ItemKind.Chore, "Take shot"),
            )

            val rows = awaitItem().rows
            assertEquals(listOf(ItemKind.Habit, ItemKind.Task, ItemKind.Chore), rows.map { it.item.kind })
            assertEquals(listOf(null, TaskId("t"), null), rows.map { it.task?.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshPullsThePlanForTheDay() = runTest {
        val repo = FakePlanRepository().apply { refreshSnapshot = listOf(taskRow("x")) }
        val component = planComponent(repo)

        component.onRefresh()
        advanceUntilIdle()

        assertEquals(1, repo.refreshCount)
        assertEquals(DATE to TZ, repo.refreshArgs.single())
        assertEquals(listOf("x"), repo.plan.value.map { it.item.id })
    }

    @Test
    fun tappingAPlanEntryEmitsOpenTaskIntent() = runTest {
        val outputs = mutableListOf<PlanComponent.Output>()
        val component = planComponent(FakePlanRepository(), outputs::add)

        component.onTaskClicked(TaskId("a"))

        assertEquals(listOf<PlanComponent.Output>(PlanComponent.Output.OpenTask(TaskId("a"))), outputs)
    }
}

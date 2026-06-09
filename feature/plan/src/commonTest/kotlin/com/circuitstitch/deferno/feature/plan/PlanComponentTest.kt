package com.circuitstitch.deferno.feature.plan

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
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
            assertEquals(emptyList(), awaitItem().tasks)
            repo.plan.value = listOf(task("a"), task("b"))
            assertEquals(listOf(TaskId("a"), TaskId("b")), awaitItem().tasks.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshPullsThePlanForTheDay() = runTest {
        val repo = FakePlanRepository().apply { refreshSnapshot = listOf(task("x")) }
        val component = planComponent(repo)

        component.onRefresh()
        advanceUntilIdle()

        assertEquals(1, repo.refreshCount)
        assertEquals(DATE to TZ, repo.refreshArgs.single())
        assertEquals(listOf(TaskId("x")), repo.plan.value.map { it.id })
    }

    @Test
    fun tappingAPlanEntryEmitsOpenTaskIntent() = runTest {
        val outputs = mutableListOf<PlanComponent.Output>()
        val component = planComponent(FakePlanRepository(), outputs::add)

        component.onTaskClicked(TaskId("a"))

        assertEquals(listOf<PlanComponent.Output>(PlanComponent.Output.OpenTask(TaskId("a"))), outputs)
    }
}

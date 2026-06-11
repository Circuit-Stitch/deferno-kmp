package com.circuitstitch.deferno.desktop.chrome

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dock-badge logic of #117, on the Linux fast path (the AWT seam is [FakeChromeBackend]). */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
class PlanBadgeTest {

    @Test
    fun badgeText_countsOnlyRemainingTasks() {
        assertNull(planBadgeText(emptyList()))
        assertEquals(
            "2",
            planBadgeText(
                listOf(
                    task("a", WorkingState.Open),
                    task("b", WorkingState.InProgress),
                    task("c", WorkingState.Done),
                ),
            ),
        )
        // A finished plan clears the badge — terminal states don't count.
        assertNull(planBadgeText(listOf(task("a", WorkingState.Done), task("b", WorkingState.Dropped))))
    }

    @Test
    fun badgeText_excludesTombstones() {
        assertNull(planBadgeText(listOf(task("a", WorkingState.Open, deleted = true))))
    }

    @Test
    fun badge_followsTheTrackedPlan_andClearsWhenThePlanFinishes() = runTest {
        val backend = FakeChromeBackend()
        val badge = PlanBadge(backend, backgroundScope)
        val plan = MutableStateFlow(listOf(task("a", WorkingState.Open), task("b", WorkingState.Open)))

        badge.trackPlan(plan)
        runCurrent()
        assertEquals("2", backend.badges.last())

        plan.value = listOf(task("a", WorkingState.Done), task("b", WorkingState.Done))
        runCurrent()
        assertNull(backend.badges.last())
    }

    @Test
    fun badge_repointsOnAccountSwitch_andClearsOnSignOut() = runTest {
        val backend = FakeChromeBackend()
        val badge = PlanBadge(backend, backgroundScope)

        badge.trackPlan(MutableStateFlow(listOf(task("a", WorkingState.Open))))
        runCurrent()
        assertEquals("1", backend.badges.last())

        // Account switch: the fresh session's plan replaces the prior one (collectLatest cancels it).
        badge.trackPlan(
            MutableStateFlow(
                listOf(task("b", WorkingState.Open), task("c", WorkingState.Open), task("d", WorkingState.Open)),
            ),
        )
        runCurrent()
        assertEquals("3", backend.badges.last())

        // Sign-out: no Active Account → the badge clears.
        badge.trackPlan(null)
        runCurrent()
        assertNull(backend.badges.last())
    }

    @Test
    fun unsupportedBadge_neverSubscribesThePlanOrWrites() = runTest {
        val backend = FakeChromeBackend(badgeSupported = false)
        val badge = PlanBadge(backend, backgroundScope)
        val plan = MutableStateFlow(listOf(task("a", WorkingState.Open)))

        badge.trackPlan(plan)
        runCurrent()

        assertTrue(backend.badges.isEmpty())
        assertEquals(0, plan.subscriptionCount.value)
    }

    private fun task(id: String, state: WorkingState, deleted: Boolean = false): Task = Task(
        id = TaskId(id),
        orgSlug = "org",
        title = "Task $id",
        workingState = state,
        dateCreated = Instant.fromEpochMilliseconds(0),
        deletedAt = if (deleted) Instant.fromEpochMilliseconds(1) else null,
    )
}

package com.circuitstitch.deferno.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.shell.DefaultMainShellComponent
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.MainShell
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI interaction test (#70, ADR-0015) for the compact-only **"More"** overflow, run on the JVM
 * via Robolectric at a compact width (`@Config`) so the nav suite folds to the bottom bar. It proves the
 * View wiring the [NavSuiteLayoutTest] pure-partition can't: tapping **More** opens the overflow sheet,
 * and selecting a secondary Destination from it switches the foreground Destination (state-preserving,
 * via the same `selectDestination` path the direct bar items use).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class MainShellNavInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun shell() = DefaultMainShellComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskRepository = DemoTaskRepository(SampleData.tasks),
        planRepository = DemoPlanRepository(emptyList()),
        authRepository = FakeAuthRepository(),
        account = sampleAccount,
        today = LocalDate(2026, 6, 6),
        timeZone = "UTC",
        coroutineContext = Dispatchers.Unconfined,
    )

    @Test
    fun more_overflow_launchesASecondaryDestination_onCompact() {
        val shell = shell()
        composeRule.setContent { DefernoTheme { MainShell(shell) } }

        // On a compact bottom bar the secondary Destinations are not direct items — they live under More.
        composeRule.onNodeWithText("More").performClick()

        // The overflow sheet lists them; picking Profile switches the foreground Destination to it.
        composeRule.onNodeWithText("Profile").performClick()

        assertEquals(Destination.Profile, shell.stack.value.active.instance.destination)
    }
}

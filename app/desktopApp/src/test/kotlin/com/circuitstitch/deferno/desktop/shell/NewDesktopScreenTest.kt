package com.circuitstitch.deferno.desktop.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.speech.ContinuityHint
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import com.circuitstitch.deferno.shell.DefaultNewComponent
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.NewComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop New-overlay render test (#87, cf. #39) — a Compose-Multiplatform UI test on the JVM-fast
 * path (no device; the desktop has no Roborazzi/Robolectric harness). It renders [NewDesktopScreen] over
 * a real [DefaultNewComponent] and asserts the explicit kind picker defaults to Task and that selecting
 * Event reveals the Event-only start field — the create rules themselves are unit-tested in app/shell.
 */
@OptIn(ExperimentalTestApi::class)
class NewDesktopScreenTest {

    private fun newComponent(): NewComponent = DefaultNewComponent(
        // The render test never submits, so the create seam stays an offline stub and [launch] does not
        // run the suspending block.
        create = { CommandResult.Offline(CommandKind.CreateItem) },
        onCreated = {},
        launch = { _ -> },
    )

    @Test
    fun kindPicker_defaultsToTask() = runComposeUiTest {
        val component = newComponent()
        setContent { Themed { NewDesktopScreen(component) } }

        // Defaults to Task (ADR-0015): the Task chip is present, the component's selected kind is Task,
        // and the Event-only start field is hidden until Event is chosen.
        onNodeWithText("Task").assertExists()
        assertEquals(ItemKind.Task, component.state.value.selectedKind)
        onNodeWithContentDescription("Event start").assertDoesNotExist()

        // With a blank title, Create is gated off by the shared canSubmit.
        onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun selectingEvent_revealsStartField() = runComposeUiTest {
        val component = newComponent()
        setContent { Themed { NewDesktopScreen(component) } }

        onNodeWithContentDescription("Event start").assertDoesNotExist()
        onNodeWithText("Event").performClick()
        onNodeWithContentDescription("Event start").assertExists()
    }

    @Test
    fun noMicAffordance_whenNoSpeechEngineWired() = runComposeUiTest {
        // The default component has no speech engine, so dictation is unavailable and no mic renders.
        setContent { Themed { NewDesktopScreen(newComponent()) } }
        onAllNodesWithContentDescription("Dictate").assertCountEquals(0)
    }

    @Test
    fun micAffordance_appearsWhenEngineAvailable_andTogglesIntoListening() = runComposeUiTest {
        // An available on-device engine is wired (Dispatchers.Unconfined runs the init availability probe
        // synchronously, so dictationAvailable is true before first composition).
        val component = DefaultNewComponent(
            create = { CommandResult.Offline(CommandKind.CreateItem) },
            onCreated = {},
            launch = { _ -> },
            speech = AvailableFakeSpeech,
            locale = "en-US",
            dictationScope = CoroutineScope(Dispatchers.Unconfined),
        )
        setContent { Themed { NewDesktopScreen(component) } }
        waitForIdle()

        // Both text fields (Title + Notes) surface a "Dictate" mic when the engine is available.
        onAllNodesWithContentDescription("Dictate").assertCountEquals(2)

        // Tapping the Title field's mic starts dictation into Title; the affordance toggles to "Stop".
        onAllNodesWithContentDescription("Dictate")[0].performClick()
        waitForIdle()
        assertEquals(DictationStatus.Listening, component.state.value.dictation)
        assertEquals(DictationField.Title, component.state.value.dictationField)
        onNodeWithContentDescription("Stop dictation").assertExists()
    }
}

/**
 * A minimal always-available [SpeechToText] for the desktop mic-affordance render test (#94): availability
 * is [SpeechAvailability.Available] and [listen] is an empty flow (the test asserts the surface enters the
 * listening state, not the streamed Transcript — the create/dictation logic is unit-tested in app/shell).
 */
private object AvailableFakeSpeech : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId("fake")
    override val rank: Int = 0
    override val supportsContinuous: Boolean = true
    override suspend fun availability(locale: String): SpeechAvailability = SpeechAvailability.Available
    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = emptyFlow()
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechError
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import com.circuitstitch.deferno.core.speech.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The New surface's **[[Dictation]]** state machine (#92, ADR-0018): availability gating, streaming
 * partials → final into the focused field, error + permission states. Pure logic — no Compose, no
 * device — driven by a [FakeSpeechToText]. Uses [UnconfinedTestDispatcher] so the engine's launched
 * availability query + listen() collection run eagerly, while [TestScope.backgroundScope] auto-cancels
 * the never-ending streaming collect at test end (no leaked coroutine).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewComponentDictationTest {

    private fun TestScope.component(
        available: SpeechAvailability = SpeechAvailability.Available,
        events: MutableSharedFlow<TranscriptEvent> = MutableSharedFlow(extraBufferCapacity = 16),
        scope: CoroutineScope? = backgroundScope,
    ) = DefaultNewComponent(
        create = { CommandResult.Offline(CommandKind.CreateItem) },
        onCreated = {},
        launch = { },
        speech = FakeSpeechToText(available = available, events = events),
        locale = "en-US",
        dictationScope = scope,
    )

    @Test
    fun dictationAvailable_reflectsEngineAvailability() = runTest(UnconfinedTestDispatcher()) {
        assertTrue(component(available = SpeechAvailability.Available).state.value.dictationAvailable)
        assertFalse(
            component(available = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing))
                .state.value.dictationAvailable,
            "no mic when the engine isn't available (e.g. model missing or non-English locale)",
        )
    }

    @Test
    fun dictationUnavailable_whenNoEngineOrScopeWired() = runTest(UnconfinedTestDispatcher()) {
        val c = DefaultNewComponent(
            create = { CommandResult.Offline(CommandKind.CreateItem) },
            onCreated = {},
            launch = { },
        )
        assertFalse(c.state.value.dictationAvailable)
        // startDictation is a no-op without an engine — never crashes, never flips state.
        c.startDictation(DictationField.Title)
        assertEquals(DictationStatus.Idle, c.state.value.dictation)
    }

    @Test
    fun startDictation_streamsPartialsIntoFieldThenSettlesFinal() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)

        c.startDictation(DictationField.Title)
        assertEquals(DictationStatus.Listening, c.state.value.dictation)
        assertEquals(DictationField.Title, c.state.value.dictationField)

        events.emit(TranscriptEvent.Partial("buy"))
        assertEquals("buy", c.state.value.title)
        assertEquals(DictationStatus.Listening, c.state.value.dictation, "still listening on a partial")

        events.emit(TranscriptEvent.Final("buy milk"))
        assertEquals("buy milk", c.state.value.title)
        assertEquals(DictationStatus.Idle, c.state.value.dictation)
        assertNull(c.state.value.dictationField, "field released once final settles")
    }

    @Test
    fun dictation_preservesExistingFieldTextAsAPrefix() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.setNotes("Reminder:")

        c.startDictation(DictationField.Notes)
        events.emit(TranscriptEvent.Partial(" call the dentist"))
        assertEquals("Reminder: call the dentist", c.state.value.notes, "partials append to what was typed")
    }

    @Test
    fun dictationError_surfacesGently_andReleasesField() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.startDictation(DictationField.Title)

        events.emit(TranscriptEvent.Error(SpeechError.Engine))
        assertEquals(DictationStatus.Error(SpeechError.Engine), c.state.value.dictation)
        assertNull(c.state.value.dictationField)
    }

    @Test
    fun stopDictation_keepsStreamedText_andLeavesListening() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.startDictation(DictationField.Title)
        events.emit(TranscriptEvent.Partial("standup"))

        c.stopDictation()
        assertEquals("standup", c.state.value.title, "the streamed text stays — it is editable text now")
        assertEquals(DictationStatus.Idle, c.state.value.dictation)
        assertNull(c.state.value.dictationField)
    }

    @Test
    fun permissionDenied_setsTheGentleStates() = runTest(UnconfinedTestDispatcher()) {
        val c = component()

        c.dictationPermissionDenied(permanentlyDenied = false)
        assertEquals(DictationStatus.PermissionDenied, c.state.value.dictation)

        c.dictationPermissionDenied(permanentlyDenied = true)
        assertEquals(DictationStatus.PermissionPermanentlyDenied, c.state.value.dictation)
    }
}

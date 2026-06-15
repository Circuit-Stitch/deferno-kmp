package com.circuitstitch.deferno.shell

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **Brain dump** recorder state machine (ADR-0027, #150; Stage 4 async rework, #212 follow-on). The
 * surface is now a deliberately simple voice **recorder**: it records the mic to a WAV ([startRecording]),
 * hands the take to the background worker on stop ([stopRecording] → Enqueued), and surfaces the gentle
 * failure/permission states — review moved to the Inbox Destination, so this component neither extracts
 * nor creates. Pure logic — no Compose, no device — driven by a [FakeRecorder] that mirrors the real
 * seam's cancel-then-finalise contract, on [UnconfinedTestDispatcher] so the launched record job runs
 * eagerly and `job.cancel()` runs its `finally` synchronously. The component's scope is [runTest]'s
 * [backgroundScope] (auto-cancelled when the test ends, so the recorder's `awaitCancellation` can't hang).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrainDumpComponentTest {

    /**
     * Mirrors the real recorder seam: it suspends for the duration of the recording and, when its job is
     * **cancelled** (Stop / teardown), finalises the WAV in its `finally` (here: flips [finalized]) — the
     * proof the take was handed off on cancel. [invocations] guards the idempotent-start contract; a
     * [failImmediately] take models the mic failing to open (a non-cancellation throw).
     */
    private class FakeRecorder(private val failImmediately: Boolean = false) {
        val started = MutableStateFlow(false)
        val finalized = MutableStateFlow(false) // set in the finally on cancel — proves the WAV was handed off
        var invocations = 0

        suspend fun record() {
            invocations++
            if (failImmediately) throw RuntimeException("mic failed to open")
            started.value = true
            try {
                awaitCancellation()
            } finally {
                finalized.value = true
            }
        }
    }

    @Test
    fun startRecording_entersRecording_andRunsTheRecorderExactlyOnce() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()

        assertEquals(Phase.Recording, c.state.value.phase)
        assertTrue(recorder.started.value, "the recorder seam was started")
        assertEquals(1, recorder.invocations, "exactly one record invocation")
    }

    @Test
    fun startRecording_whileRecording_isIdempotent_oneInvocation() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()
        c.startRecording() // a job is already active — a no-op

        assertEquals(Phase.Recording, c.state.value.phase)
        assertEquals(1, recorder.invocations, "a second start while recording never launches a new take")
    }

    @Test
    fun stopRecording_enqueues_andFinalisesTheTake() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()
        c.stopRecording()

        assertEquals(Phase.Enqueued, c.state.value.phase)
        assertTrue(recorder.finalized.value, "cancel runs the seam's finally ⇒ the take was handed off")
    }

    @Test
    fun stopRecording_whenNotRecording_isANoOp_staysIdle() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.stopRecording() // nothing to stop — never crashes, never flips state

        assertEquals(Phase.Idle, c.state.value.phase)
        assertEquals(0, recorder.invocations, "no take was ever started")
    }

    @Test
    fun recorderThatThrows_landsTheGentleFailedState() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder(failImmediately = true)
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()

        assertEquals(Phase.Failed, c.state.value.phase, "a non-cancellation throw surfaces as Failed")
    }

    @Test
    fun dictationPermissionDenied_setsTheGentleStates_andCancelsAnyTake() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()
        c.dictationPermissionDenied(permanentlyDenied = false)
        assertEquals(Phase.PermissionDenied, c.state.value.phase)
        assertTrue(recorder.finalized.value, "a denial cancels the in-flight take ⇒ it finalises")

        c.dictationPermissionDenied(permanentlyDenied = true)
        assertEquals(Phase.PermissionPermanentlyDenied, c.state.value.phase)
    }

    @Test
    fun dismiss_routesToTheHost() = runTest(UnconfinedTestDispatcher()) {
        var done = false
        val c = DefaultBrainDumpComponent(
            onDone = { done = true },
            scope = backgroundScope,
        )

        c.dismiss()

        assertTrue(done, "dismiss invokes the onDone callback")
    }

    @Test
    fun openDictationPermissionSettings_routesToTheHost() = runTest(UnconfinedTestDispatcher()) {
        var opened = false
        val c = DefaultBrainDumpComponent(
            onDone = {},
            scope = backgroundScope,
            onOpenDictationPermissionSettings = { opened = true },
        )

        c.openDictationPermissionSettings()

        assertTrue(opened, "the settings deep-link is host-routed")
    }

    @Test
    fun cancelCapture_finalisesTheTake_andIsIdempotent() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        val c = DefaultBrainDumpComponent(
            record = recorder::record,
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()
        c.cancelCapture()
        assertTrue(recorder.finalized.value, "teardown still runs the seam's finalise — a real take isn't lost")

        c.cancelCapture() // a second teardown never crashes
        assertFalse(c.state.value.phase == Phase.Enqueued, "cancelCapture is silent — no UI phase change")
    }

    @Test
    fun defaultNoOpRecorder_recordsWithoutCrashing_theInertDesktopPath() = runTest(UnconfinedTestDispatcher()) {
        // No record seam supplied (desktop / no on-device engine): startRecording must still be safe.
        val c = DefaultBrainDumpComponent(
            onDone = {},
            scope = backgroundScope,
        )

        c.startRecording()

        assertEquals(Phase.Recording, c.state.value.phase)
    }
}

package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.FakeSidecarPermissionPort
import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.SidecarPermissionCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The [SidecarDictationPermissionSettings] pane pick (#120) on the Linux fast path: the "Open System
 * Settings" affordance introspects **live at click time** and deep-links the pane of whichever
 * dictation permission is actually foreclosed — mic first, then Speech — never a stale guess.
 */
class SidecarDictationPermissionSettingsTest {

    private val mac = "Mac OS X"
    private val micPane = "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
    private val speechPane = "x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition"

    @Test
    fun picksTheMicrophonePaneWhenTheMicIsForeclosed() = runTest {
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Microphone to PermissionStatusValue.DENIED),
        )
        assertEquals(micPane, SidecarDictationPermissionSettings(permissions, osName = mac).deepLink())
    }

    @Test
    fun picksTheSpeechPaneWhenOnlySpeechIsForeclosed() = runTest {
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Speech to PermissionStatusValue.RESTRICTED),
        )
        assertEquals(speechPane, SidecarDictationPermissionSettings(permissions, osName = mac).deepLink())
    }

    @Test
    fun fallsBackToTheMicrophonePaneWhenNothingIsIntrospectablyBlocked() = runTest {
        // A stale denial note (the person already flipped Settings) still opens a real pane, not nothing.
        val permissions = FakeSidecarPermissionPort()
        assertEquals(micPane, SidecarDictationPermissionSettings(permissions, osName = mac).deepLink())
    }

    @Test
    fun deepLinksNothingOffMacOsWithoutDialing() = runTest {
        val permissions = FakeSidecarPermissionPort(
            statuses = mutableMapOf(SidecarPermissionCapabilities.Microphone to PermissionStatusValue.DENIED),
        )
        assertNull(SidecarDictationPermissionSettings(permissions, osName = "Linux").deepLink())
    }
}

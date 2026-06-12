package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode

/**
 * Call-recording [SettingsWriter] for the command-registry tests (#173) — the User-setting sibling of
 * [FakeOccurrenceWriter]. Records each settings write so a test can assert [CommandExecutor] routed the
 * per-field [SettingsCommand] to the writer with the right operands, and that a non-settings command
 * never touched it.
 */
class FakeSettingsWriter : SettingsWriter {
    val calls = mutableListOf<Call>()

    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) { calls += Call.SetTheme(family, mode) }
    override suspend fun setTracking(enabled: Boolean) { calls += Call.SetTracking(enabled) }
    override suspend fun setDragAndDrop(enabled: Boolean) { calls += Call.SetDragAndDrop(enabled) }
    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) {
        calls += Call.SetDoneVisibility(globalSeconds, dashboardSeconds)
    }

    sealed interface Call {
        data class SetTheme(val family: ThemeFamily, val mode: ThemeMode) : Call
        data class SetTracking(val enabled: Boolean) : Call
        data class SetDragAndDrop(val enabled: Boolean) : Call
        data class SetDoneVisibility(val globalSeconds: Long?, val dashboardSeconds: Long?) : Call
    }
}

package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.data.calendar.OccurrenceWriter
import com.circuitstitch.deferno.core.data.create.CreateWriter
import com.circuitstitch.deferno.core.data.plan.PlanWriter
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.data.task.TaskWriter
import com.circuitstitch.deferno.core.scopes.AccountScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * AccountScope binding for the command registry's dispatch site (ADR-0007/0014): [CommandExecutor]
 * routes pure-data commands to the Account's [TaskWriter] / [PlanWriter] / [CreateWriter] /
 * [OccurrenceWriter] / [SettingsWriter] write seams, so it lives in the same scope as the per-Account
 * data layer it drives.
 */
@ContributesTo(AccountScope::class)
interface CommandBindings {
    @Provides
    @SingleIn(AccountScope::class)
    fun commandExecutor(
        taskWriter: TaskWriter,
        planWriter: PlanWriter,
        createWriter: CreateWriter,
        occurrenceWriter: OccurrenceWriter,
        settingsWriter: SettingsWriter,
    ): CommandExecutor = CommandExecutor(taskWriter, planWriter, createWriter, occurrenceWriter, settingsWriter)
}

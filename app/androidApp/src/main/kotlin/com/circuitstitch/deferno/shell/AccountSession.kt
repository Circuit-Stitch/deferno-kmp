package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.AddToPlan
import com.circuitstitch.deferno.core.domain.command.CommandExecutor
import com.circuitstitch.deferno.core.domain.command.taskCommandFor
import com.circuitstitch.deferno.core.di.AccountComponent
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import kotlinx.datetime.LocalDate

/**
 * The per-Account data the [RootComponent]'s Main shell is built over (ADR-0014): the offline-first
 * repositories the Views observe, plus the offline write paths (add-to-plan and working-state edits go
 * through the command executor → optimistic apply + outbox enqueue). It is the seam the shell depends
 * on, so the shell stays testable on fakes while production builds it from the real [AccountComponent].
 *
 * Rebuilt when the Active Account switches (the [RootComponent] re-keys its Main child), so each
 * session holds exactly one Account's repositories — the per-Account isolation boundary (ADR-0002).
 */
interface AccountSession {
    val taskRepository: TaskRepository
    val planRepository: PlanRepository

    /** Add [taskId] to the ([date], [tz]) plan — optimistic apply + outbox enqueue (ADR-0001/0007). */
    suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String)

    /**
     * The working-state write seam the Tasks detail drives (#73). Maps a target [WorkingState] to its
     * one lifecycle Command and dispatches it through the command executor (optimistic apply + outbox
     * enqueue), gated by the supplied cached row — so the shell drives the offline write without the
     * feature layer touching the command registry directly (mirrors the [addToPlan] wrapper).
     */
    val workingStateEditor: WorkingStateEditor
}

/**
 * Production [AccountSession] over the per-Account [AccountComponent] DI graph (ADR-0014). The
 * component owns the Account's encrypted DB, repositories, outbox, and command executor; this adapts
 * the slice the shell needs.
 */
internal class AccountComponentSession(private val component: AccountComponent) : AccountSession {
    override val taskRepository: TaskRepository get() = component.taskRepository
    override val planRepository: PlanRepository get() = component.planRepository

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        component.commandExecutor.execute(AddToPlan(taskId, date, tz))
    }

    override val workingStateEditor: WorkingStateEditor =
        commandWorkingStateEditor(component.commandExecutor)
}

/**
 * A [WorkingStateEditor] backed by a [CommandExecutor]: converts a target [WorkingState] to its one
 * lifecycle Command ([taskCommandFor]) and dispatches it with the cached row for the pre-flight gate
 * (ADR-0007). Shared by production and tests so the mapping isn't duplicated.
 */
internal fun commandWorkingStateEditor(executor: CommandExecutor): WorkingStateEditor =
    WorkingStateEditor { id: TaskId, target: WorkingState, current: Task? ->
        executor.execute(taskCommandFor(id, target), current = current)
    }

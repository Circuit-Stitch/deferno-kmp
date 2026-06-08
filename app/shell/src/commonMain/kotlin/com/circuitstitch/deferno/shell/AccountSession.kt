package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.settings.SettingsWriter
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.AddToPlan
import com.circuitstitch.deferno.core.domain.command.ClearOccurrence
import com.circuitstitch.deferno.core.domain.command.CommandExecutor
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.domain.command.MarkOccurrence
import com.circuitstitch.deferno.core.domain.command.RescheduleOccurrence
import com.circuitstitch.deferno.core.domain.command.taskCommandFor
import com.circuitstitch.deferno.core.di.AccountComponent
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
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

    /**
     * The Active Account's settings — observed for the Settings Destination (#72) and the app-wide
     * **live theme** (the theme StateFlow the root derives from this drives `DefernoTheme`).
     */
    val settingsRepository: SettingsRepository

    /** The settings write seam (#72): optimistic local apply + outbox enqueue for `PATCH /auth/me/settings`. */
    val settingsWriter: SettingsWriter

    /** Add [taskId] to the ([date], [tz]) plan — optimistic apply + outbox enqueue (ADR-0001/0007). */
    suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String)

    /**
     * The working-state write seam the Tasks detail drives (#73). Maps a target [WorkingState] to its
     * one lifecycle Command and dispatches it through the command executor (optimistic apply + outbox
     * enqueue), gated by the supplied cached row — so the shell drives the offline write without the
     * feature layer touching the command registry directly (mirrors the [addToPlan] wrapper).
     */
    val workingStateEditor: WorkingStateEditor

    /** The Calendar Destination's windowed feed read source (#74) — the month grid + day agenda observe it. */
    val calendarRepository: CalendarRepository

    /**
     * The occurrence-act seam the Calendar drives (#74): mark / clear / reschedule a firing through the
     * command executor (optimistic apply + outbox enqueue), so the feature layer never touches the
     * registry directly — the firing-level mirror of [workingStateEditor].
     */
    val occurrenceEditor: OccurrenceEditor

    /**
     * Create a new item online (ADR-0016): routes [payload] through the command executor's online-only
     * [CreateItem] command. Returns the [CommandResult] so the New surface can show the created item
     * (Accepted), a gentle "reconnect to save" (Offline), or a server error (Failed) — never a silent
     * failure. **Nothing is enqueued offline** (ADR-0016).
     */
    suspend fun create(payload: CreateItem.Payload): CommandResult
}

/**
 * Production [AccountSession] over the per-Account [AccountComponent] DI graph (ADR-0014). The
 * component owns the Account's encrypted DB, repositories, outbox, and command executor; this adapts
 * the slice the shell needs.
 */
class AccountComponentSession(private val component: AccountComponent) : AccountSession {
    override val taskRepository: TaskRepository get() = component.taskRepository
    override val planRepository: PlanRepository get() = component.planRepository
    override val settingsRepository: SettingsRepository get() = component.settingsRepository
    override val settingsWriter: SettingsWriter get() = component.settingsWriter
    override val calendarRepository: CalendarRepository get() = component.calendarRepository

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        component.commandExecutor.execute(AddToPlan(taskId, date, tz))
    }

    override val workingStateEditor: WorkingStateEditor =
        commandWorkingStateEditor(component.commandExecutor)

    override val occurrenceEditor: OccurrenceEditor =
        commandOccurrenceEditor(component.commandExecutor)

    override suspend fun create(payload: CreateItem.Payload): CommandResult =
        component.commandExecutor.execute(CreateItem(payload))
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

/**
 * An [OccurrenceEditor] backed by a [CommandExecutor] (#74): each act maps to its occurrence Command
 * and dispatches it (optimistic apply + outbox enqueue). Shared by production and tests so the mapping
 * isn't duplicated — the firing-level mirror of [commandWorkingStateEditor].
 */
internal fun commandOccurrenceEditor(executor: CommandExecutor): OccurrenceEditor =
    object : OccurrenceEditor {
        override suspend fun mark(itemId: String, action: OccurrenceAction) {
            executor.execute(MarkOccurrence(itemId, action))
        }

        override suspend fun clear(itemId: String) {
            executor.execute(ClearOccurrence(itemId))
        }

        override suspend fun reschedule(itemId: String, newDate: LocalDate) {
            executor.execute(RescheduleOccurrence(itemId, newDate))
        }
    }

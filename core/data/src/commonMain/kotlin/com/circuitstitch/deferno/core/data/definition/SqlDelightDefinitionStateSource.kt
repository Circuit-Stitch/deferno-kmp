package com.circuitstitch.deferno.core.data.definition

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.data.recurring.toDefinitionStateOrDefault
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The production [DefinitionStateSource] over the SQLDelight [DefernoDatabase] (ADR-0053 decision 4,
 * #390).
 *
 * It reads the three recurring tables **directly**, through narrow `(id, definition_state)` queries,
 * rather than composing the three per-kind local stores. The reading wants one enum per definition;
 * going through `HabitLocalStore.observeActive()` and its siblings would rebuild the whole domain
 * model — fourteen recurrence columns and a sealed `Cadence` per row — on every emission of a flow the
 * day agenda re-collects on every write to any of those tables. The queries are kind-qualified in the
 * `.sq` because SQLDelight hoists a multi-column query's row type into one shared package.
 *
 * Tombstoned rows are excluded by the queries themselves, so a soft-deleted definition resolves to
 * `null` — which the resolver reads as Unknown, never as Missed. An unrecognised stored token degrades
 * to [DefinitionState.Active], the same defensive rule every other recurring decode uses
 * (`RecurringEntityCodec.kt`).
 */
class SqlDelightDefinitionStateSource(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DefinitionStateSource {

    override fun observeAll(): Flow<Map<DefinitionRef, DefinitionState>> = combine(
        db.habitEntityQueries.selectHabitDefinitionStates().asFlow().mapToList(dispatcher),
        db.choreEntityQueries.selectChoreDefinitionStates().asFlow().mapToList(dispatcher),
        db.eventEntityQueries.selectEventDefinitionStates().asFlow().mapToList(dispatcher),
    ) { habits, chores, events ->
        buildMap {
            habits.forEach { put(DefinitionRef(ItemKind.Habit, it.id), it.definition_state.toDefinitionStateOrDefault()) }
            chores.forEach { put(DefinitionRef(ItemKind.Chore, it.id), it.definition_state.toDefinitionStateOrDefault()) }
            events.forEach { put(DefinitionRef(ItemKind.Event, it.id), it.definition_state.toDefinitionStateOrDefault()) }
        }
    }

    override suspend fun get(kind: ItemKind, definitionId: String): DefinitionState? {
        val token = when (kind) {
            // A Task has no light switch at all — its lifecycle is a WorkingState — so there is nothing
            // to look up rather than nothing found. Both answer `null`, and the resolver reads either as
            // Unknown, which is the honest reading for a firing whose definition this device cannot see.
            ItemKind.Task -> null
            ItemKind.Habit -> db.habitEntityQueries.selectHabitDefinitionStateById(definitionId).executeAsOneOrNull()
            ItemKind.Chore -> db.choreEntityQueries.selectChoreDefinitionStateById(definitionId).executeAsOneOrNull()
            ItemKind.Event -> db.eventEntityQueries.selectEventDefinitionStateById(definitionId).executeAsOneOrNull()
        }
        return token?.toDefinitionStateOrDefault()
    }
}

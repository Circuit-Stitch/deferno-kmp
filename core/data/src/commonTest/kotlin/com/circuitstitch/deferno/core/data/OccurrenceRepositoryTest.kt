package com.circuitstitch.deferno.core.data

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OfflineOccurrenceRepository
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Occurrence
import com.circuitstitch.deferno.core.model.OccurrenceId
import com.circuitstitch.deferno.core.model.OccurrenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline-first Occurrence read repository (ADR-0001, #71) — the firing-level sibling of
 * `RecurringRepositoryTest`. It observes its local-store `Flow` only and re-emits on upsert, so an
 * occurrence pulled from the kind-scoped endpoint surfaces with no manual refresh (AC #4). The fake
 * store mirrors `FakeHabitLocalStore`'s re-emitting `MutableStateFlow` shape.
 */
class OccurrenceRepositoryTest {

    @Test
    fun observesForADefinitionAndReEmitsOnUpsert() = runTest {
        val store = FakeOccurrenceLocalStore()
        val repo = OfflineOccurrenceRepository(store)

        repo.observeForDefinition("evt-9").test {
            assertTrue(awaitItem().isEmpty())
            store.upsert(
                Occurrence(OccurrenceId("occ-1"), "evt-9", ItemKind.Event, LocalDate(2026, 6, 8), OccurrenceState.Scheduled),
            )
            assertEquals(listOf(OccurrenceId("occ-1")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeForDefinitionIsScopedToThatDefinition() = runTest {
        val mine = Occurrence(OccurrenceId("a"), "evt-9", ItemKind.Event, LocalDate(2026, 6, 8), OccurrenceState.Scheduled)
        val other = Occurrence(OccurrenceId("b"), "evt-OTHER", ItemKind.Event, LocalDate(2026, 6, 8), OccurrenceState.Scheduled)
        val repo = OfflineOccurrenceRepository(FakeOccurrenceLocalStore(listOf(mine, other)))

        repo.observeForDefinition("evt-9").test {
            assertEquals(listOf(OccurrenceId("a")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/** In-memory [OccurrenceLocalStore] backed by a re-emitting [MutableStateFlow] (mirrors FakeHabitLocalStore). */
private class FakeOccurrenceLocalStore(initial: List<Occurrence> = emptyList()) : OccurrenceLocalStore {
    private val rows = MutableStateFlow(initial.associateBy { it.id })
    override fun observeForDefinition(definitionId: String): Flow<List<Occurrence>> =
        rows.map { it.values.filter { o -> o.definitionId == definitionId }.sortedBy { o -> o.date } }
    override fun observe(id: OccurrenceId): Flow<Occurrence?> = rows.map { it[id] }
    override suspend fun get(id: OccurrenceId): Occurrence? = rows.value[id]
    override suspend fun upsert(occurrence: Occurrence) { rows.value = rows.value.toMutableMap().also { it[occurrence.id] = occurrence } }
    override suspend fun delete(id: OccurrenceId) { rows.value = rows.value.toMutableMap().also { it.remove(id) } }
}

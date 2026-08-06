package com.circuitstitch.deferno.core.data.definition

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.item.ItemDetailRead
import com.circuitstitch.deferno.core.data.item.ItemDetailRemoteSource
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.mergeCoverage
import com.circuitstitch.deferno.core.model.toDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The kind-neutral recurring-definition read (#383).
 *
 * The load-bearing behaviour under test is **not** the fan-out — it is what `hydrate` does with the
 * detail read's dated answer, because that is the only thing in this client that writes
 * [[Occurrence coverage]]. Get it wrong in either direction and the recurring detail is silently
 * useless: record nothing and today reads Unknown forever; record a fact from the server's placeholder
 * and the detail claims a resolution the server never had.
 */
class DefinitionRepositoryTest {

    private val today = LocalDate(2026, 6, 15)
    private val ref = ItemRef("h", ItemKind.Habit)

    // The backend's "nothing recorded for this date" sentinel — it sends the field with a zero id
    // rather than omitting it (the openapi `ItemDetail.today_occurrence` description says so).
    private val placeholderId = "00000000-0000-0000-0000-000000000000"

    private fun habit(id: String = "h", title: String = "walk") = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private class Fixture(
        habitRows: Map<HabitId, Habit> = emptyMap(),
        val read: ItemDetailRead? = null,
    ) {
        val habits = FakeHabitLocalStore(habitRows)
        val chores = FakeChoreLocalStore()
        val events = FakeEventLocalStore()
        val facts = RecordingFactStore()
        val coverage = RecordingCoverageStore()
        val remote = object : ItemDetailRemoteSource {
            var calls = 0
            override suspend fun fetch(ref: ItemRef): RemoteSnapshot<ItemDetailRead> {
                calls++
                return read?.let { RemoteSnapshot.Available(it) } ?: RemoteSnapshot.Unavailable
            }
        }
        val repository = OfflineDefinitionRepository(habits, chores, events, remote, facts, coverage)
    }

    @Test
    fun observeProjectsTheCachedHabitToTheKindNeutralDefinition() = runTest {
        val f = Fixture(mapOf(HabitId("h") to habit()))

        f.repository.observe(ref).test {
            assertEquals(habit().toDefinition(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEmitsNullForAnUnknownDefinition() = runTest {
        Fixture().repository.observe(ref).test {
            assertNull(awaitItem(), "a definition this device has never cached is null, not an error")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A Task is not a definition. Answering `null` rather than throwing keeps a caller holding a
     * generic [ItemRef] honest without making every call site pre-check the kind.
     */
    @Test
    fun observeEmitsNullForATaskRefWithoutTouchingAnyStore() = runTest {
        val f = Fixture(mapOf(HabitId("h") to habit()))

        f.repository.observe(ItemRef("h", ItemKind.Task)).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The central case. The server answered for today with a real stored record, so BOTH tables are
     * written: the resolution as a fact, and the day as coverage.
     */
    @Test
    fun hydrateLandsARealRecordAsAFactAndRecordsCoverage() = runTest {
        val f = Fixture(
            habitRows = mapOf(HabitId("h") to habit()),
            read = ItemDetailRead(
                definition = habit().toDefinition(),
                todayFact = OccurrenceFact(ItemKind.Habit, "h", today, OccurrenceResolution.DoneOnTime),
                answeredForToday = true,
            ),
        )

        f.repository.hydrate(ref, today)

        assertEquals(
            OccurrenceResolution.DoneOnTime,
            f.facts.get(ItemKind.Habit, "h", today)?.resolution,
        )
        assertEquals(listOf(OccurrenceCoverage(ItemKind.Habit, "h", today, today)), f.coverage.get(ItemKind.Habit, "h"))
    }

    /**
     * The placeholder case, and the reason [ItemDetailRead.answeredForToday] is a separate flag rather
     * than `todayFact != null`. The server answered — it just had nothing on record — so the day IS
     * covered, and coverage is exactly what turns that absence from *unknown* into *unresolved*.
     * Writing a fact here would manufacture a resolution; writing no coverage would leave the detail
     * reading Unknown forever, which is the bug this repository exists to close.
     */
    @Test
    fun hydrateRecordsCoverageButNoFactForThePlaceholderAnswer() = runTest {
        val f = Fixture(
            habitRows = mapOf(HabitId("h") to habit()),
            read = ItemDetailRead(
                definition = habit().toDefinition(),
                todayFact = null,
                answeredForToday = true,
            ),
        )

        f.repository.hydrate(ref, today)

        assertNull(f.facts.get(ItemKind.Habit, "h", today), "a placeholder is not a stored resolution")
        assertTrue(f.coverage.get(ItemKind.Habit, "h").single().covers(today), "but the day WAS answered for")
    }

    /** Offline: nothing is written, nothing is claimed, and the cached row still reads. */
    @Test
    fun hydrateWritesNothingWhenTheNetworkIsGone() = runTest {
        val f = Fixture(habitRows = mapOf(HabitId("h") to habit()), read = null)

        val extras = f.repository.hydrate(ref, today)

        assertNull(extras, "an unavailable read yields no extras")
        assertNull(f.facts.get(ItemKind.Habit, "h", today))
        assertTrue(f.coverage.get(ItemKind.Habit, "h").isEmpty(), "we never looked, so we never claim to have")
        f.repository.observe(ref).test {
            assertEquals("walk", awaitItem()?.title, "the cached definition still renders")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The chain rides the read and is handed back — never cached (ADR-0053: the snapshot drops eras). */
    @Test
    fun hydrateReturnsTheChainAndOriginLabelWithoutCachingThem() = runTest {
        val chain = SeriesChain(head = "h", requested = "h", segments = emptyList(), truncated = true)
        val f = Fixture(
            habitRows = mapOf(HabitId("h") to habit()),
            read = ItemDetailRead(definition = habit().toDefinition(), chain = chain, originLabel = "acme/repo#4"),
        )

        val extras = f.repository.hydrate(ref, today)

        assertEquals(chain, extras?.chain)
        assertEquals("acme/repo#4", extras?.originLabel)
    }

    /**
     * The refreshed detail is MERGED onto the cached row, not swapped for a projection of it.
     * [com.circuitstitch.deferno.core.model.RecurringDefinition] deliberately carries only what the
     * detail renders, so a round-trip through it would blank every column it does not carry — here,
     * the Habit's `orgSlug` and `dateCreated`.
     */
    @Test
    fun hydrateMergesOntoTheCachedRowRatherThanReplacingIt() = runTest {
        val cached = habit(title = "stale")
        val f = Fixture(
            habitRows = mapOf(HabitId("h") to cached),
            read = ItemDetailRead(definition = habit(title = "fresh").toDefinition()),
        )

        f.repository.hydrate(ref, today)

        val row = f.habits.get(HabitId("h"))!!
        assertEquals("fresh", row.title, "the detail's fields win")
        assertEquals(cached.orgSlug, row.orgSlug, "and the columns the projection never carried survive")
        assertEquals(cached.dateCreated, row.dateCreated)
    }
}

/** A minimal in-memory fact store — only the members this test exercises do real work. */
private class RecordingFactStore : OccurrenceFactLocalStore {
    private val rows = MutableStateFlow<List<OccurrenceFact>>(emptyList())

    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> =
        rows.map { all -> all.filter { it.date == date } }

    override fun observeInRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<OccurrenceFact>> = rows.map { all ->
        all.filter { it.kind == kind && it.definitionId == definitionId && it.date >= from && it.date <= to }
    }

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate): Flow<OccurrenceFact?> =
        rows.map { all -> all.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date } }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? =
        rows.value.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date }

    override suspend fun upsert(fact: OccurrenceFact) {
        rows.value = rows.value.filterNot {
            it.kind == fact.kind && it.definitionId == fact.definitionId && it.date == fact.date
        } + fact
    }

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        rows.value = rows.value.filterNot { it.kind == kind && it.definitionId == definitionId && it.date == date }
    }

    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {
        rows.value = rows.value.filterNot {
            it.kind == kind && it.definitionId == definitionId && it.date >= from && it.date <= to
        } + facts
    }

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)
}

/**
 * In-memory coverage, folded through the REAL `mergeCoverage` — so the fake cannot accidentally
 * swallow a gap the production store would keep, which is the one behaviour whose loss would silently
 * turn Unknown into Missed.
 */
private class RecordingCoverageStore : OccurrenceCoverageLocalStore {
    private val ranges = MutableStateFlow<List<OccurrenceCoverage>>(emptyList())

    override fun observeCovering(date: LocalDate): Flow<List<OccurrenceCoverage>> =
        ranges.map { all -> all.filter { it.covers(date) } }

    override suspend fun get(kind: ItemKind, definitionId: String): List<OccurrenceCoverage> =
        ranges.value.filter { it.kind == kind && it.definitionId == definitionId }

    override suspend fun record(coverage: OccurrenceCoverage) {
        ranges.value = ranges.value.mergeCoverage(coverage)
    }

    override suspend fun clear(kind: ItemKind, definitionId: String) {
        ranges.value = ranges.value.filterNot { it.kind == kind && it.definitionId == definitionId }
    }
}

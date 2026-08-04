package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Which date ranges this device has actually synced, per recurring definition (CONTEXT.md →
 * "Occurrence coverage", ADR-0053 decision 4).
 *
 * **Split from [OccurrenceFactLocalStore] deliberately.** ADR-0053 names two tables because one
 * cannot answer the question: with facts alone, "no row for 3 March" is ambiguous between *nothing
 * was recorded* and *this device has never looked*, and the occurrence-state reading would report
 * every unsynced past day as Missed. That ambiguity is the thing this epic exists to remove.
 */
interface OccurrenceCoverageLocalStore {

    /**
     * Every synced range containing [date], across all definitions — the day agenda's companion read
     * to [OccurrenceFactLocalStore.observeOn].
     */
    fun observeCovering(date: LocalDate): Flow<List<OccurrenceCoverage>>

    /** One definition's synced ranges, disjoint and ascending. */
    suspend fun get(kind: ItemKind, definitionId: String): List<OccurrenceCoverage>

    /**
     * Record a window that has just been synced, coalescing it with existing ranges through
     * `List<OccurrenceCoverage>.mergeCoverage` — which joins only ranges that overlap or are
     * genuinely adjacent, so a gap between two fetched windows is never swallowed into "synced".
     */
    suspend fun record(coverage: OccurrenceCoverage)

    /** Drop everything known about one definition's coverage (its rows were invalidated wholesale). */
    suspend fun clear(kind: ItemKind, definitionId: String)
}

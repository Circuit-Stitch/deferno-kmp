package com.circuitstitch.deferno.core.data.occurrence

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * The local source of truth for **stored occurrence resolutions** (ADR-0001, ADR-0053 decision 4) —
 * what the server has on record for a dated firing, and nothing that is a function of today.
 *
 * Keyed `(kind, definitionId, date)`, the identity the write path has always used through
 * `OccurrenceTargets.of`. This replaces the old `OccurrenceLocalStore`, whose primary key was the
 * server's occurrence UUID — an id a habit occurrence does not have on the wire at all, so read and
 * write could never be joined.
 *
 * [transaction] exists deliberately: the outbox occurrence writer's optimistic apply is a
 * read-modify-write, and the move onto this table must not inherit the un-transacted version of that
 * (the Task writer has always wrapped its own, so a concurrent reconcile cannot interleave).
 */
interface OccurrenceFactLocalStore {

    /**
     * Every fact recorded on one calendar day, across **all** definitions — what a day agenda reads.
     * A per-definition range read cannot serve it: the agenda has many definitions and one day.
     */
    fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>>

    /** One definition's facts across an inclusive window — what a history strip reads. */
    fun observeInRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<OccurrenceFact>>

    /** One firing, observed. Emits `null` while nothing is on record for that date. */
    fun observe(kind: ItemKind, definitionId: String, date: LocalDate): Flow<OccurrenceFact?>

    /** One firing, read once. `null` when nothing is on record. */
    suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact?

    /** Record (or overwrite) one firing's resolution. */
    suspend fun upsert(fact: OccurrenceFact)

    /** Forget one firing's resolution — the local half of a Clear. */
    suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate)

    /**
     * Full-replace one definition's synced window (the `deleteInRange` + insert idiom the calendar
     * cache already uses). A row the server no longer reports inside `[from, to]` is gone, not merely
     * absent from this response — which is what makes a server-side Clear converge.
     */
    suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    )

    /** Run [block] against this store inside one database transaction. */
    suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit)
}

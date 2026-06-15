package com.circuitstitch.deferno.core.data.braindump

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.BrainDumpDraftEntity
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import com.circuitstitch.deferno.core.data.recurring.toLocalTimeOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Local-only persistence for on-device Brain dump draft Tasks (ADR-0027 async redesign). Unlike the
 * synced caches (Task/Habit/…), a draft has no remote source and no reconcile: the worker writes drafts,
 * the Inbox Destination (ADR-0015 amendment) observes + accepts/dismisses them, and a draft leaves the
 * Ready queue on accept (committed through the ordinary online create) or dismiss. So this is one flat SQLDelight
 * class — no LocalStore/Repository split and no fake; its SQL<->domain round-trip is proved directly
 * against in-memory SQLite (ADR-0006 JVM-fast path). Reads are observe-via-Flow only (ADR-0001).
 */
class BrainDumpDraftRepository(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val queries get() = db.brainDumpDraftEntityQueries

    fun observeDrafts(): Flow<List<BrainDumpDraft>> =
        queries.selectAll().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    suspend fun upsert(draft: BrainDumpDraft) {
        queries.insertOrReplace(
            id = draft.id.value,
            title = draft.title,
            notes = draft.notes,
            complete_by = draft.completeBy?.toString(),
            deadline_time_of_day = draft.deadlineTimeOfDay?.toString(),
            status = draft.status.name,
            created_at = draft.createdAt.toString(),
        )
    }

    suspend fun delete(id: BrainDumpDraftId) {
        queries.deleteById(id.value)
    }

    suspend fun clear() {
        queries.deleteAll()
    }
}

private fun BrainDumpDraftEntity.toDomain(): BrainDumpDraft = BrainDumpDraft(
    id = BrainDumpDraftId(id),
    title = title,
    notes = notes,
    completeBy = complete_by?.let(LocalDate::parse),
    deadlineTimeOfDay = deadline_time_of_day.toLocalTimeOrNull(),
    status = status.toStatusOrReady(),
    createdAt = Instant.parse(created_at),
)

// Defensive: an unknown/legacy status string degrades to Ready rather than throwing (cf. the settings
// mapping) — never `enumValueOf`, which throws on an unrecognised value.
private fun String.toStatusOrReady(): BrainDumpDraftStatus =
    BrainDumpDraftStatus.entries.firstOrNull { it.name == this } ?: BrainDumpDraftStatus.Ready

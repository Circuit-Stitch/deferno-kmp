package com.circuitstitch.deferno.core.data.attachment

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.LocalAttachmentEntity
import kotlin.time.Instant

/**
 * Local-only persistence for on-device attachments (#210): records each attachment in the per-Account DB and
 * stores/serves its bytes through the [AttachmentBytesStore]. Unlike the synced caches there is no remote
 * source and no reconcile — the bytes stay on the device. One flat class, like `BrainDumpDraftRepository`;
 * its SQL<->domain round-trip is proved directly against in-memory SQLite (ADR-0006 JVM-fast path).
 * AccountScope — the DB is per-Account, so Account B never sees Account A's rows.
 *
 * The bytes [locator] is the attachment id (a flat key in the store's app-private dir); per-Account isolation
 * rides the per-Account DB index + the app-private sandbox. Retention/cleanup of orphaned bytes is a
 * follow-up (#210). The caller supplies the id + `createdAt` (no `Clock.System` — injected).
 *
 * This is the **foundation** seam (#210): the on-device counterpart to the backend presign/commit path,
 * which the selectable storage provider chooses between. Routing the Task-detail attach flow / brain-dump
 * audio retention (#211) onto it is the consumer's job; feedback attachments never use it (always backend).
 */
class LocalAttachmentRepository(
    private val db: DefernoDatabase,
    private val bytesStore: AttachmentBytesStore,
) {
    private val queries get() = db.localAttachmentEntityQueries

    /**
     * Store [bytes] on-device and record the attachment, returning the saved record. The bytes are written
     * first (under locator = [id]) so a recorded row always has its bytes; [provider] defaults to
     * [StorageProviderId.OnDevice].
     */
    suspend fun save(
        id: String,
        taskId: String?,
        filename: String,
        mime: String,
        bytes: ByteArray,
        createdAt: Instant,
        provider: StorageProviderId = StorageProviderId.OnDevice,
    ): LocalAttachment {
        bytesStore.write(id, bytes)
        val record = LocalAttachment(
            id = id,
            taskId = taskId,
            provider = provider,
            locator = id,
            filename = filename,
            mime = mime,
            size = bytes.size.toLong(),
            caption = null,
            createdAt = createdAt,
        )
        queries.insertOrReplace(
            id = record.id,
            task_id = record.taskId,
            provider = record.provider.value,
            locator = record.locator,
            filename = record.filename,
            mime = record.mime,
            size = record.size,
            caption = record.caption,
            created_at = record.createdAt.toString(),
        )
        return record
    }

    /** The attachment record [id], or `null` if unknown. */
    suspend fun get(id: String): LocalAttachment? =
        queries.selectById(id).executeAsOneOrNull()?.toDomain()

    /** The on-device attachments for [taskId], newest first. */
    suspend fun forTask(taskId: String): List<LocalAttachment> =
        queries.selectByTask(taskId).executeAsList().map { it.toDomain() }

    /** The bytes for attachment [id], read entirely on-device (no network), or `null` if absent. */
    suspend fun bytes(id: String): ByteArray? {
        val locator = queries.selectById(id).executeAsOneOrNull()?.locator ?: return null
        return bytesStore.read(locator)
    }

    /** Delete the record and its bytes. */
    suspend fun delete(id: String) {
        val locator = queries.selectById(id).executeAsOneOrNull()?.locator
        queries.deleteById(id)
        if (locator != null) bytesStore.delete(locator)
    }
}

private fun LocalAttachmentEntity.toDomain(): LocalAttachment = LocalAttachment(
    id = id,
    taskId = task_id,
    provider = StorageProviderId(provider),
    locator = locator,
    filename = filename,
    mime = mime,
    size = size,
    caption = caption,
    createdAt = Instant.parse(created_at),
)

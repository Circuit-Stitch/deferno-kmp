package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.outbox.CommentTargets
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The offline-first Task-comment repository (ADR-0043, #197). It promotes the last online-only surface
 * to the offline-first pattern: the thread is **observed from the cache** ([observe]) and never read from
 * the network, while a task-detail open fires a best-effort [refresh] that reconciles the server thread
 * into the cache. A permanently-gone server degrades this to "no new comments arrive" — the cached thread
 * stands as truth, and an un-synced write is never lost (ADR-0043 offline-first invariant).
 *
 * Writes are not here — they apply optimistically to the [CommentLocalStore] and replay through the
 * outbox (the comment mutations, #197 slice 6); the store's `Flow` re-emits, so a post/edit/delete shows
 * instantly.
 */
interface CommentRepository {

    /** The live thread for [taskId], oldest-first, from the cache (ADR-0001) — never the network. */
    fun observe(taskId: TaskId): Flow<List<Comment>>

    /** Best-effort on-open refresh; reconciles the server thread into the cache, or no-ops if unreachable. */
    suspend fun refresh(taskId: TaskId)

    companion object {
        /** An empty repository (no comments, no-op refresh) — the default a component/shell builds over. */
        val NONE: CommentRepository = object : CommentRepository {
            override fun observe(taskId: TaskId): Flow<List<Comment>> = flowOf(emptyList())
            override suspend fun refresh(taskId: TaskId) {}
        }
    }
}

/**
 * The production [CommentRepository]: observe the [localStore], and on [refresh] reconcile the
 * [remoteSource] snapshot into it — **outbox-aware** so it can never clobber an un-synced write (#143).
 *
 * The reconcile (mirroring [com.circuitstitch.deferno.core.data.settings.OfflineSettingsRepository]):
 * every server comment is upserted, and every cached comment the server no longer returns is dropped —
 * **except** any comment id with a pending outbox mutation (a `comment:<id>` edit/delete or a
 * `comment-create:` create). The outbox is the pending-state oracle (there is no pending column), so an
 * optimistic edit, a local tombstone, or an un-synced new comment survives an on-open refresh that fires
 * before the outbox drains. `Unavailable` (offline / server error) skips the whole reconcile.
 */
class DefaultCommentRepository(
    private val localStore: CommentLocalStore,
    private val remoteSource: CommentRemoteSource,
    private val outbox: OutboxStore,
) : CommentRepository {

    override fun observe(taskId: TaskId): Flow<List<Comment>> = localStore.observe(taskId)

    override suspend fun refresh(taskId: TaskId) {
        val server = when (val snapshot = remoteSource.fetchComments(taskId)) {
            is RemoteSnapshot.Available -> snapshot.value
            RemoteSnapshot.Unavailable -> return
        }
        // The #143 clobber-guard: the ids an un-synced write is optimistically holding. Read after the
        // fetch (like the settings reconcile) to shrink the enqueue-during-fetch race window.
        val protected = outbox.pending().mapNotNullTo(mutableSetOf()) { CommentTargets.protectedId(it.target) }
        val serverIds = server.mapTo(mutableSetOf()) { it.id }

        for (comment in server) {
            if (comment.id !in protected) localStore.upsert(comment)
        }
        // Drop cached comments the server no longer has (a server-side delete), never a protected one.
        for (id in localStore.idsForTask(taskId)) {
            if (id !in serverIds && id !in protected) localStore.deleteById(id)
        }
    }
}

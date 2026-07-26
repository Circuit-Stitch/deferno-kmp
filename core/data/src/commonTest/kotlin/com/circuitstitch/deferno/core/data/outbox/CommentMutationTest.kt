package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comment outbox intents (ADR-0043): pins each intent's target keying + minimal wire request. The
 * targets are the load-bearing contract the refresh's #143 guard and the processor's create route key on
 * (via [CommentTargets]).
 *
 * Each also pins its [OutboxRequest.acceptsActivityStamp] declaration (#364) beside its endpoint — all
 * three comment routes take the `activity` sibling, and the `{}` bodies the two soft-deletes render exist
 * for no other reason than to give it somewhere to land.
 */
class CommentMutationTest {

    @Test
    fun postTargetsCommentCreateAndPostsBodyOnlyToTheTaskThread() {
        val request = PostComment(TaskId("t-1"), clientId = "c-client", body = "hello").let {
            assertEquals("comment-create:t-1:c-client", it.target)
            it.toRequest()
        }
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("tasks", "t-1", "comments"), request.path)
        // NO id (the backend does not accept one), but is_private IS required — omitting it 422s the create.
        assertEquals("""{"body":"hello","is_private":false}""", request.body)
        assertTrue(request.acceptsActivityStamp)
    }

    @Test
    fun editTargetsTaskAndCommentAndPatchesTheBody() {
        val request = EditComment(taskId = "t-1", commentId = "c-1", body = "edited").let {
            // The taskId tags the target (so the Activity feed resolves the item) but is NOT sent.
            assertEquals("comment:t-1:c-1", it.target)
            it.toRequest()
        }
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(listOf("comments", "c-1"), request.path)
        assertEquals("""{"body":"edited"}""", request.body)
        assertTrue(request.acceptsActivityStamp)
        // An unresolved task falls back to the legacy id-only target.
        assertEquals("comment:c-1", EditComment(taskId = null, commentId = "c-1", body = "x").target)
    }

    @Test
    fun deleteTargetsTaskAndCommentAndPostsToTheDeleteSubresource() {
        // #364: `DELETE comments/{id}` is gone (no alias) — deletes became POST soft-deletes so the
        // request can carry Activity-ledger metadata. The empty-object body is what the outbox
        // choke-point merges the client-minted `activity` stamp into.
        val request = DeleteComment(taskId = "t-1", commentId = "c-1").let {
            assertEquals("comment:t-1:c-1", it.target)
            it.toRequest()
        }
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("comments", "c-1", "delete"), request.path)
        assertEquals("{}", request.body)
        // The empty body exists so the stamp has somewhere to land — declaring the route is what makes
        // the choke-point actually put one there (#364).
        assertTrue(request.acceptsActivityStamp)
        assertEquals("comment:c-1", DeleteComment(taskId = null, commentId = "c-1").target)
    }
}

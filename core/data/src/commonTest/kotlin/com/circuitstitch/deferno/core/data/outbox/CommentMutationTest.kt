package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The comment outbox intents (ADR-0043): pins each intent's target keying + minimal wire request. The
 * targets are the load-bearing contract the refresh's #143 guard and the processor's create route key on
 * (via [CommentTargets]).
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
        assertEquals("""{"body":"hello"}""", request.body) // NO id — the backend does not accept one
    }

    @Test
    fun editTargetsTheCommentIdAndPatchesTheBody() {
        val request = EditComment(commentId = "c-1", body = "edited").let {
            assertEquals("comment:c-1", it.target)
            it.toRequest()
        }
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(listOf("comments", "c-1"), request.path)
        assertEquals("""{"body":"edited"}""", request.body)
    }

    @Test
    fun deleteTargetsTheCommentIdAndSendsNoBody() {
        val request = DeleteComment(commentId = "c-1").let {
            assertEquals("comment:c-1", it.target)
            it.toRequest()
        }
        assertEquals(OutboxMethod.Delete, request.method)
        assertEquals(listOf("comments", "c-1"), request.path)
        assertNull(request.body)
    }
}

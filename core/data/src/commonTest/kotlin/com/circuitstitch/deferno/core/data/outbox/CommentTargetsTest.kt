package com.circuitstitch.deferno.core.data.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The comment outbox target scheme (ADR-0043, #260). The edit/delete target now carries the task id
 * (`comment:<taskId>:<commentId>`) so the Activity feed can resolve which item a "Commented on …" row
 * touched, while staying **backward-compatible** with the legacy id-only `comment:<commentId>` shape —
 * the #143 clobber-guard's [CommentTargets.protectedId] must read the comment id from both.
 */
class CommentTargetsTest {

    @Test
    fun editTagsTheTaskWhenKnownAndFallsBackToIdOnly() {
        assertEquals("comment:t-1:c-1", CommentTargets.edit("t-1", "c-1"))
        assertEquals("comment:c-1", CommentTargets.edit(null, "c-1")) // unresolved task ⇒ legacy shape
        assertEquals("comment:c-1", CommentTargets.edit("", "c-1")) // blank task ⇒ legacy shape
    }

    @Test
    fun protectedIdReadsTheCommentIdFromBothShapesAndTheCreateClientId() {
        assertEquals("c-1", CommentTargets.protectedId("comment:t-1:c-1")) // new: last segment
        assertEquals("c-1", CommentTargets.protectedId("comment:c-1")) // legacy
        assertEquals("client-1", CommentTargets.protectedId("comment-create:t-1:client-1"))
        assertNull(CommentTargets.protectedId("task:x"))
    }

    @Test
    fun taskIdReadsCreateAndNewEditButNotLegacyOrNonComment() {
        assertEquals("t-1", CommentTargets.taskId("comment-create:t-1:client-1"))
        assertEquals("t-1", CommentTargets.taskId("comment:t-1:c-1"))
        assertNull(CommentTargets.taskId("comment:c-1")) // legacy: no task segment
        assertNull(CommentTargets.taskId("task:t-1")) // not a comment
        assertNull(CommentTargets.taskId("settings"))
    }
}

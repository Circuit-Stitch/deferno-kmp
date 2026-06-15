package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.CommentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The `CommentDto` → domain `Comment` mapping. The string ids become typed [TaskId]/[UserId], the
 * RFC3339 strings become [Instant]s, and the nullable body (an encrypted-only comment carries none)
 * passes through.
 */
class CommentMapperTest {

    private fun dto(
        id: String = "c-1",
        taskId: String = "t-1",
        createdBy: String = "u-1",
        body: String? = "Looks good",
        editedAt: String? = null,
        deletedAt: String? = null,
    ) = CommentDto(
        id = id,
        taskId = taskId,
        body = body,
        createdBy = createdBy,
        createdAt = "2026-04-17T10:00:00Z",
        editedAt = editedAt,
        deletedAt = deletedAt,
        isPrivate = false,
    )

    @Test
    fun mapsEveryFieldOntoTheDomainComment() {
        val c = dto(editedAt = "2026-04-17T11:00:00Z").toDomain()

        assertEquals("c-1", c.id)
        assertEquals(TaskId("t-1"), c.taskId)
        assertEquals("Looks good", c.body)
        assertEquals(UserId("u-1"), c.createdBy)
        assertEquals(Instant.parse("2026-04-17T10:00:00Z"), c.createdAt)
        assertEquals(Instant.parse("2026-04-17T11:00:00Z"), c.editedAt)
        assertNull(c.deletedAt)
        assertEquals(false, c.isPrivate)
    }

    @Test
    fun anEncryptedOnlyCommentMapsToANullBody() {
        assertNull(dto(body = null).toDomain().body)
    }

    @Test
    fun aBlankIdentityIdFailsAtTheSeam() {
        assertFailsWith<IllegalArgumentException> { dto(taskId = "").toDomain() }
        assertFailsWith<IllegalArgumentException> { dto(createdBy = "").toDomain() }
    }
}

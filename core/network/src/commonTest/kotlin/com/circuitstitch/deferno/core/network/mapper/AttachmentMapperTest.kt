package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.AttachmentViewDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The `AttachmentViewDto` → domain `Attachment` mapping. The `created_by` string becomes a typed
 * [UserId], the RFC3339 `created_at` an [Instant], and the optional caption passes through.
 */
class AttachmentMapperTest {

    private fun dto(
        createdBy: String = "u-1",
        caption: String? = "Receipt",
    ) = AttachmentViewDto(
        id = "a-1",
        filename = "receipt.pdf",
        mime = "application/pdf",
        size = 12_345L,
        url = "https://files.example/a-1",
        caption = caption,
        createdBy = createdBy,
        createdAt = "2026-04-17T10:00:00Z",
    )

    @Test
    fun mapsEveryFieldOntoTheDomainAttachment() {
        val a = dto().toDomain()

        assertEquals("a-1", a.id)
        assertEquals("receipt.pdf", a.filename)
        assertEquals("application/pdf", a.mime)
        assertEquals(12_345L, a.size)
        assertEquals("https://files.example/a-1", a.url)
        assertEquals("Receipt", a.caption)
        assertEquals(UserId("u-1"), a.createdBy)
        assertEquals(Instant.parse("2026-04-17T10:00:00Z"), a.createdAt)
    }

    @Test
    fun absentCaptionMapsToNull() {
        assertNull(dto(caption = null).toDomain().caption)
    }

    @Test
    fun aBlankCreatorIdFailsAtTheSeam() {
        assertFailsWith<IllegalArgumentException> { dto(createdBy = "").toDomain() }
    }
}

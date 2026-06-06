package com.circuitstitch.deferno.core.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the [Task] domain projection: the tombstone read helper and the summary defaults
 * a list-sourced row carries before it is hydrated (ADR-0001, #22).
 */
class TaskTest {
    private fun task(deletedAt: Instant? = null) = Task(
        id = TaskId("948bcfab-063d-4499-b2de-f21801bc6f9c"),
        orgSlug = "u-e4h2qk",
        title = "<title>",
        workingState = WorkingState.Open,
        dateCreated = Instant.parse("2026-05-20T16:11:42.625684725Z"),
        deletedAt = deletedAt,
    )

    @Test
    fun isDeletedReflectsTombstone() {
        assertFalse(task().isDeleted)
        assertTrue(task(deletedAt = Instant.parse("2026-06-01T00:00:00Z")).isDeleted)
    }

    @Test
    fun summaryRowDefaultsToUnhydratedWithEmptyEnrichment() {
        val t = task()
        assertEquals(HydrationState.Summary, t.hydration)
        assertEquals(emptyList(), t.children)
        assertEquals(emptyList(), t.labels)
        assertEquals(null, t.description)
        assertEquals(null, t.ownerOrgId)
        assertEquals(null, t.ref)
    }
}

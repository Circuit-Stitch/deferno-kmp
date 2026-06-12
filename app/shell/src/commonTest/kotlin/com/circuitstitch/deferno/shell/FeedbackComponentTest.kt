package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.feedback.FeedbackDraft
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the in-app Feedback surface logic (#375): the [FeedbackState.canSubmit] gate, the
 * shell→repository draft mapping (the category token + attachment bytes), and the online-only status
 * transitions (Sent ⇒ dismiss, Offline/Failed ⇒ a gentle note, nothing queued — ADR-0016).
 */
class FeedbackComponentTest {

    private class FakeFeedbackRepository(
        private val result: FeedbackResult,
        val drafts: MutableList<FeedbackDraft> = mutableListOf(),
    ) : FeedbackRepository {
        override suspend fun submit(draft: FeedbackDraft): FeedbackResult {
            drafts += draft
            return result
        }
    }

    @Test
    fun cannotSubmitWithoutSubjectAndBody() {
        val repo = FakeFeedbackRepository(FeedbackResult.Sent)
        val component = DefaultFeedbackComponent(repo, onDone = {}, launch = {})

        assertFalse(component.state.value.canSubmit, "blank subject + body cannot submit")
        component.setSubject("Crash on open")
        assertFalse(component.state.value.canSubmit, "subject alone is not enough")
        component.setBody("It crashes.")
        assertTrue(component.state.value.canSubmit)
    }

    @Test
    fun submit_mapsTheDraftAndDismissesOnSent() = runTest {
        val repo = FakeFeedbackRepository(FeedbackResult.Sent)
        var dismissed = false
        val component = DefaultFeedbackComponent(
            repository = repo,
            onDone = { dismissed = true },
            launch = { block -> launch { block() } },
        )
        component.setCategory(FeedbackCategory.Idea)
        component.setSubject("  Dark mode  ")
        component.setBody("  Please add it  ")
        component.addAttachments(listOf(FeedbackFile("log.txt", "text/plain", byteArrayOf(1, 2, 3))))

        component.submit()
        advanceUntilIdle()

        assertEquals(1, repo.drafts.size)
        val draft = repo.drafts.single()
        assertEquals("idea", draft.category, "the category maps to its wire token")
        assertEquals("Dark mode", draft.subject, "subject is trimmed")
        assertEquals("Please add it", draft.body, "body is trimmed")
        assertEquals(1, draft.attachments.size)
        assertEquals("log.txt", draft.attachments.single().filename)
        assertEquals(FeedbackStatus.Sent, component.state.value.status)
        assertTrue(dismissed, "a successful send dismisses the surface")
    }

    @Test
    fun submit_offlineShowsTheGentleNoteAndDoesNotDismiss() = runTest {
        var dismissed = false
        val component = DefaultFeedbackComponent(
            repository = FakeFeedbackRepository(FeedbackResult.Offline),
            onDone = { dismissed = true },
            launch = { block -> launch { block() } },
        )
        component.setSubject("s")
        component.setBody("b")

        component.submit()
        advanceUntilIdle()

        assertEquals(FeedbackStatus.Offline, component.state.value.status)
        assertFalse(dismissed, "offline keeps the surface open so the user can retry")
    }

    @Test
    fun submit_failedSurfacesTheServerMessage() = runTest {
        val component = DefaultFeedbackComponent(
            repository = FakeFeedbackRepository(FeedbackResult.Failed("Too big")),
            onDone = {},
            launch = { block -> launch { block() } },
        )
        component.setSubject("s")
        component.setBody("b")

        component.submit()
        advanceUntilIdle()

        assertEquals(FeedbackStatus.Failed("Too big"), component.state.value.status)
    }

    @Test
    fun removeAttachmentDropsTheRightFile() {
        val component = DefaultFeedbackComponent(FakeFeedbackRepository(FeedbackResult.Sent), onDone = {}, launch = {})
        component.addAttachments(
            listOf(
                FeedbackFile("a.txt", "text/plain", byteArrayOf(1)),
                FeedbackFile("b.txt", "text/plain", byteArrayOf(2)),
            ),
        )
        component.removeAttachment(0)
        assertEquals(listOf("b.txt"), component.state.value.attachments.map { it.filename })
    }
}

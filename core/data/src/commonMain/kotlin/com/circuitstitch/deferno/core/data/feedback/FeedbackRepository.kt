package com.circuitstitch.deferno.core.data.feedback

/** A file the user attached to feedback (#375): its [filename], MIME [contentType], and raw [bytes]. */
class FeedbackAttachment(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/** The composed feedback being submitted: a [category]/[subject]/[body] comment + optional [attachments]. */
data class FeedbackDraft(
    val category: String,
    val subject: String,
    val body: String,
    val attachments: List<FeedbackAttachment> = emptyList(),
)

/** The outcome of a feedback submit — the online-only result shape the create flow uses (ADR-0016). */
sealed interface FeedbackResult {
    data object Sent : FeedbackResult

    /** No usable network — nothing was sent; the user can retry when back online. */
    data object Offline : FeedbackResult

    /** The server (or an attachment upload) rejected it — a typed [reason] the View localizes;
     *  [message] keeps the English words for the SwiftUI bridges. */
    data class Failed(
        val message: String,
        val reason: Reason = Reason.ServerMessage,
        /** The HTTP status for the *Failed arms whose message shows it, else null. */
        val statusCode: Int? = null,
    ) : FeedbackResult {
        enum class Reason { PrepareAttachments, UploadFailed, SendFailed, AppOutOfDate, ServerMessage }
    }
}

/**
 * Submits in-app Help → Feedback (#375): presign each attachment, PUT its bytes **byte-exact** to the
 * returned URL (the SSE-KMS pair + content-type S3 signed in), then POST the comment referencing the
 * uploaded ids. AppScope — it rides the shared authed `HttpClient`, whose bearer plugin attaches the
 * Active Account's PAT per request (ADR-0012), so it needs no per-Account wiring.
 */
interface FeedbackRepository {
    suspend fun submit(draft: FeedbackDraft): FeedbackResult
}

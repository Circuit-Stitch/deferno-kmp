package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.feedback.FeedbackAttachment
import com.circuitstitch.deferno.core.data.feedback.FeedbackDraft
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A file the user picked to attach (#375), read into [bytes] by the platform View. A shell-local type
 * so the View layers ([com.circuitstitch.deferno.shell.ui]) bind to it without depending on `core:data`;
 * the component maps it to the repository's `FeedbackAttachment` at submit.
 */
data class FeedbackFile(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * The in-app **Help → Feedback** surface logic (#375): a category/subject/body comment plus file
 * attachments, submitted online-only through the [FeedbackRepository] (presign → byte-exact PUT →
 * submit). Compose-free so the platform View ([com.circuitstitch.deferno.FeedbackScreen] on Android)
 * is a thin render of this state and the flow is unit-testable without a UI. Like the **New** create
 * surface it is online-only (ADR-0016): nothing is queued offline — a transport failure shows a gentle
 * "reconnect to send".
 */
interface FeedbackComponent {
    val state: StateFlow<FeedbackState>

    fun setCategory(category: FeedbackCategory)
    fun setSubject(subject: String)
    fun setBody(body: String)

    /** Add files the platform picker resolved (already read into bytes). */
    fun addAttachments(files: List<FeedbackFile>)

    /** Drop the attachment at [index] (the user removed it before sending). */
    fun removeAttachment(index: Int)

    /** Send the feedback via the online-only path; the View reacts to the resulting [FeedbackStatus]. */
    fun submit()

    /** Close the surface (the host finishes the screen). */
    fun dismiss()
}

/** The feedback category the comment is filed under — a small fixed set; [wire] is the API token. */
enum class FeedbackCategory(val wire: String, val label: String) {
    Bug("bug", "Bug"),
    Idea("idea", "Idea"),
    Question("question", "Question"),
    Other("other", "Other"),
}

/** The feedback form's render state. */
data class FeedbackState(
    val category: FeedbackCategory = FeedbackCategory.Bug,
    val subject: String = "",
    val body: String = "",
    val attachments: List<FeedbackFile> = emptyList(),
    val status: FeedbackStatus = FeedbackStatus.Editing,
) {
    /** Send is enabled with a non-blank subject + body, and not mid-send. Attachments are optional. */
    val canSubmit: Boolean
        get() = subject.isNotBlank() && body.isNotBlank() && status != FeedbackStatus.Submitting
}

/** Where the feedback surface is in its send lifecycle. */
sealed interface FeedbackStatus {
    data object Editing : FeedbackStatus
    data object Submitting : FeedbackStatus
    data object Sent : FeedbackStatus

    /** Offline (ADR-0016): the gentle "reconnect to send"; nothing was queued. */
    data object Offline : FeedbackStatus

    /** A server/upload rejection — a typed [reason] the View localizes; [message] keeps the
     *  English words for the SwiftUI bridges. */
    data class Failed(
        val message: String,
        val reason: FeedbackResult.Failed.Reason = FeedbackResult.Failed.Reason.ServerMessage,
        val statusCode: Int? = null,
    ) : FeedbackStatus
}

/**
 * Default [FeedbackComponent]. [repository] is the AppScope feedback service; [onDone] closes the
 * surface (invoked on a successful send and on dismiss); [launch] runs the suspending submit on the
 * host's scope.
 */
class DefaultFeedbackComponent(
    private val repository: FeedbackRepository,
    private val onDone: () -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
) : FeedbackComponent {

    private val _state = MutableStateFlow(FeedbackState())
    override val state: StateFlow<FeedbackState> = _state

    override fun setCategory(category: FeedbackCategory) =
        _state.update { it.copy(category = category, status = FeedbackStatus.Editing) }

    override fun setSubject(subject: String) =
        _state.update { it.copy(subject = subject, status = FeedbackStatus.Editing) }

    override fun setBody(body: String) =
        _state.update { it.copy(body = body, status = FeedbackStatus.Editing) }

    override fun addAttachments(files: List<FeedbackFile>) =
        _state.update { it.copy(attachments = it.attachments + files, status = FeedbackStatus.Editing) }

    override fun removeAttachment(index: Int) = _state.update {
        if (index in it.attachments.indices) {
            it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }, status = FeedbackStatus.Editing)
        } else {
            it
        }
    }

    override fun submit() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        _state.update { it.copy(status = FeedbackStatus.Submitting) }
        launch {
            val draft = FeedbackDraft(
                category = snapshot.category.wire,
                subject = snapshot.subject.trim(),
                body = snapshot.body.trim(),
                attachments = snapshot.attachments.map {
                    FeedbackAttachment(filename = it.filename, contentType = it.contentType, bytes = it.bytes)
                },
            )
            when (val result = repository.submit(draft)) {
                FeedbackResult.Sent -> {
                    _state.update { it.copy(status = FeedbackStatus.Sent) }
                    onDone()
                }
                FeedbackResult.Offline -> _state.update { it.copy(status = FeedbackStatus.Offline) }
                is FeedbackResult.Failed -> _state.update { it.copy(status = FeedbackStatus.Failed(result.message, result.reason, result.statusCode)) }
            }
        }
    }

    override fun dismiss() = onDone()
}

package com.circuitstitch.deferno.core.data.feedback

import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.UploadHttpClient
import com.circuitstitch.deferno.core.network.dto.FeedbackPresignBatchRequestDto
import com.circuitstitch.deferno.core.network.dto.FeedbackPresignBatchResponseDto
import com.circuitstitch.deferno.core.network.dto.PresignRequestDto
import com.circuitstitch.deferno.core.network.dto.SubmitFeedbackRequestDto
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonElement

/**
 * The [FeedbackRepository] over the shared authed [HttpClient] + the bare [UploadHttpClient] (#375).
 * Three steps, short-circuiting on the first failure: presign → PUT each file byte-exact → submit the
 * comment with the returned attachment ids. A transport failure at any step degrades to
 * [FeedbackResult.Offline] (nothing half-committed the user can't see); a server rejection to
 * [FeedbackResult.Failed].
 */
class KtorFeedbackRepository(
    private val client: HttpClient,
    private val uploadClient: UploadHttpClient,
) : FeedbackRepository {

    override suspend fun submit(draft: FeedbackDraft): FeedbackResult {
        val attachmentIds = if (draft.attachments.isEmpty()) {
            emptyList()
        } else {
            val presign = client.requestApi<FeedbackPresignBatchResponseDto> {
                method = HttpMethod.Post
                url { appendPathSegments("feedback", "attachments", "presign") }
                contentType(ContentType.Application.Json)
                setBody(
                    FeedbackPresignBatchRequestDto(
                        files = draft.attachments.map {
                            PresignRequestDto(it.filename, it.contentType, it.bytes.size.toLong())
                        },
                    ),
                )
            }
            val presigned = when (presign) {
                is ApiResult.Success -> presign.data.attachments
                is ApiResult.Failure -> return presign.error.toFeedbackResult()
            }
            if (presigned.size != draft.attachments.size) {
                return FeedbackResult.Failed("Couldn't prepare the attachments. Try again.", FeedbackResult.Failed.Reason.PrepareAttachments)
            }
            // Upload each file byte-exact, in request order (the presign list is parallel to it).
            presigned.forEachIndexed { i, p ->
                when (val up = upload(p.putUrl, p.headers, draft.attachments[i])) {
                    FeedbackResult.Sent -> Unit
                    else -> return up
                }
            }
            presigned.map { it.attachmentId }
        }

        val submit = client.requestApi<JsonElement> {
            method = HttpMethod.Post
            url { appendPathSegments("feedback") }
            contentType(ContentType.Application.Json)
            setBody(
                SubmitFeedbackRequestDto(
                    category = draft.category,
                    subject = draft.subject,
                    body = draft.body,
                    attachmentIds = attachmentIds.ifEmpty { null },
                ),
            )
        }
        return when (submit) {
            is ApiResult.Success -> FeedbackResult.Sent
            is ApiResult.Failure -> submit.error.toFeedbackResult()
        }
    }

    /** PUT one file's bytes to its presigned URL, sending the signed [headers] byte-exact (#375). */
    private suspend fun upload(url: String, headers: Map<String, String>, file: FeedbackAttachment): FeedbackResult {
        val response: HttpResponse = try {
            uploadClient.client.put(url) {
                var contentTypeSet = false
                for ((k, v) in headers) {
                    if (k.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                        runCatching { contentType(ContentType.parse(v)) }.onSuccess { contentTypeSet = true }
                    } else {
                        header(k, v)
                    }
                }
                // LocalFs (dev) signs no content-type; fall back to the file's own so the body type is right.
                if (!contentTypeSet) runCatching { contentType(ContentType.parse(file.contentType)) }
                setBody(file.bytes)
            }
        } catch (t: Throwable) {
            return FeedbackResult.Offline
        }
        return if (response.status.isSuccess()) {
            FeedbackResult.Sent
        } else {
            FeedbackResult.Failed("Attachment upload failed (${response.status.value}).", FeedbackResult.Failed.Reason.UploadFailed, response.status.value)
        }
    }
}

/** Map a network [ApiError] to the user-facing [FeedbackResult] — transport ⇒ Offline, else Failed. */
private fun ApiError.toFeedbackResult(): FeedbackResult = when (this) {
    is ApiError.Transport -> FeedbackResult.Offline
    is ApiError.Endpoint -> FeedbackResult.Failed(message, FeedbackResult.Failed.Reason.ServerMessage)
    is ApiError.Status -> FeedbackResult.Failed("Couldn't send feedback ($status).", FeedbackResult.Failed.Reason.SendFailed, status)
    is ApiError.UnsupportedVersion -> FeedbackResult.Failed("This app is out of date — please update.", FeedbackResult.Failed.Reason.AppOutOfDate)
}

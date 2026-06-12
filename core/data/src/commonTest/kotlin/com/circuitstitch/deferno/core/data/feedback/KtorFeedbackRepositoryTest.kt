package com.circuitstitch.deferno.core.data.feedback

import com.circuitstitch.deferno.core.network.UploadHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the feedback orchestration (#375): presign → byte-exact PUT → submit over the API
 * client + the bare upload client. The load-bearing assertions are that the presigned `headers` go out
 * on the PUT **byte-exact** (the SSE-KMS pair + content-type — a missing one is a 403 on real S3) and
 * that the uploaded attachment id is referenced in the submit body.
 */
class KtorFeedbackRepositoryTest {

    private val draftWithFile = FeedbackDraft(
        category = "bug",
        subject = "Crash",
        body = "It crashed.",
        attachments = listOf(FeedbackAttachment("shot.png", "image/png", byteArrayOf(9, 8, 7))),
    )

    @Test
    fun submit_presignsUploadsByteExactThenSubmitsWithTheAttachmentId() = runTest {
        var presignPath: String? = null
        var submitBody: String? = null
        val api = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/feedback/attachments/presign") -> {
                    presignPath = request.url.encodedPath
                    respondJson(
                        """{"version":"0.1","data":{"attachments":[{
                           "attachment_id":"att-1",
                           "put_url":"https://s3.example.test/bucket/att-1?sig=abc",
                           "expires_at":"2026-06-12T00:00:00Z",
                           "headers":{"x-amz-server-side-encryption":"aws:kms","Content-Type":"image/png"}
                        }]}}""",
                    )
                }
                request.url.encodedPath.endsWith("/feedback") -> {
                    submitBody = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                    respondJson("""{"version":"0.1","data":{"id":"fb-1"}}""", HttpStatusCode.Created)
                }
                else -> respondJson("""{"version":"0.1","error":{"code":"not_found","message":"?"}}""", HttpStatusCode.NotFound)
            }
        }

        var putUrl: String? = null
        var putBytes: ByteArray? = null
        var sseHeader: String? = null
        var putContentType: String? = null
        val upload = UploadHttpClient(
            HttpClient(
                MockEngine { request ->
                    putUrl = request.url.toString()
                    val content = request.body as OutgoingContent.ByteArrayContent
                    putBytes = content.bytes()
                    sseHeader = request.headers["x-amz-server-side-encryption"]
                    putContentType = content.contentType?.toString()
                    respond("", HttpStatusCode.OK)
                },
            ) { expectSuccess = false },
        )

        val result = KtorFeedbackRepository(api, upload).submit(draftWithFile)

        assertEquals(FeedbackResult.Sent, result)
        assertTrue(presignPath != null, "presign was called")
        assertEquals("https://s3.example.test/bucket/att-1?sig=abc", putUrl, "PUT goes to the presigned URL")
        assertEquals(listOf<Byte>(9, 8, 7), putBytes?.toList(), "the file bytes are PUT")
        assertEquals("aws:kms", sseHeader, "the signed SSE header is sent byte-exact (#375)")
        assertTrue(putContentType?.startsWith("image/png") == true, "the signed content-type is sent")
        assertTrue(submitBody?.contains("\"att-1\"") == true, "the submit references the uploaded id")
    }

    @Test
    fun submit_withNoAttachments_skipsPresignAndPosts() = runTest {
        var presignCalled = false
        var submitCalled = false
        val api = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/presign") -> {
                    presignCalled = true
                    respondJson("""{"version":"0.1","data":{"attachments":[]}}""")
                }
                request.url.encodedPath.endsWith("/feedback") -> {
                    submitCalled = true
                    respondJson("""{"version":"0.1","data":{"id":"fb-2"}}""", HttpStatusCode.Created)
                }
                else -> respondJson("""{"version":"0.1","data":null}""")
            }
        }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        val result = KtorFeedbackRepository(api, upload)
            .submit(FeedbackDraft(category = "bug", subject = "s", body = "b"))

        assertEquals(FeedbackResult.Sent, result)
        assertTrue(submitCalled)
        assertTrue(!presignCalled, "no attachments ⇒ no presign round-trip")
    }

    @Test
    fun submit_uploadRejected_failsWithoutSubmitting() = runTest {
        var submitCalled = false
        val api = apiClient { request ->
            when {
                request.url.encodedPath.endsWith("/presign") -> respondJson(
                    """{"version":"0.1","data":{"attachments":[{
                       "attachment_id":"att-1","put_url":"https://s3.example.test/x","expires_at":"2026-06-12T00:00:00Z","headers":{}
                    }]}}""",
                )
                request.url.encodedPath.endsWith("/feedback") -> {
                    submitCalled = true
                    respondJson("""{"version":"0.1","data":{}}""", HttpStatusCode.Created)
                }
                else -> respondJson("""{"version":"0.1","data":null}""")
            }
        }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("denied", HttpStatusCode.Forbidden) }) { expectSuccess = false })

        val result = KtorFeedbackRepository(api, upload).submit(draftWithFile)

        assertTrue(result is FeedbackResult.Failed, "a rejected upload fails the whole submit")
        assertTrue(!submitCalled, "feedback is not submitted when an attachment upload fails")
    }

    // --- helpers ---

    private fun apiClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/api/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

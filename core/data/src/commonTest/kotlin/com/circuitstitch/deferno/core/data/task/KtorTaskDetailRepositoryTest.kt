package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.UploadHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.url
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorTaskDetailRepository] (the Task detail's online-only attachments — comments + item
 * history moved offline-first in ADR-0043), driven by Ktor's MockEngine on the JVM-fast path (ADR-0006) —
 * no real network. Proves each call hits the right path/method, maps the envelope through the DTO->domain
 * mappers, and degrades to `null`/`false` on failure rather than throwing.
 */
class KtorTaskDetailRepositoryTest {

    private val attachmentsEnvelope = """
        {"version":"0.1","data":[
            {"id":"a1","filename":"receipt.pdf","mime":"application/pdf","size":1234,
             "url":"https://files/a1","created_by":"u1","created_at":"2026-04-17T10:00:00Z"}
        ]}
    """.trimIndent()

    @Test
    fun attachmentsMapsToDomain() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(attachmentsEnvelope) })

        val attachments = repo.attachments(TaskId("t1"))

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t1/attachments") == true)
        assertEquals(listOf("receipt.pdf"), attachments?.map { it.filename })
        assertEquals(1234L, attachments?.first()?.size)
    }

    @Test
    fun attachmentsReturnsNullOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.Unauthorized) })
        assertNull(repo.attachments(TaskId("t1")))
    }

    @Test
    fun uploadAttachmentsPresignsPutsByteExactThenCommits() = runTest {
        var presignCalled = false
        var commitBody: String? = null
        val api = client { request ->
            when {
                request.url.encodedPath.endsWith("/tasks/t1/attachments/presign") -> {
                    presignCalled = true
                    respondJson(
                        """{"version":"0.1","data":{"attachments":[{
                           "attachment_id":"att-1",
                           "put_url":"https://s3.example.test/bucket/att-1?sig=abc",
                           "expires_at":"2026-06-12T00:00:00Z",
                           "headers":{"x-amz-server-side-encryption":"aws:kms","Content-Type":"application/pdf"}
                        }]}}""",
                    )
                }
                request.url.encodedPath.endsWith("/tasks/t1/attachments") -> {
                    commitBody = (request.body as? TextContent)?.text
                    respondJson(attachmentsEnvelope, HttpStatusCode.Created)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        var putUrl: String? = null
        var putBytes: ByteArray? = null
        var sseHeader: String? = null
        val upload = UploadHttpClient(
            HttpClient(
                MockEngine { request ->
                    putUrl = request.url.toString()
                    val content = request.body as OutgoingContent.ByteArrayContent
                    putBytes = content.bytes()
                    sseHeader = request.headers["x-amz-server-side-encryption"]
                    respond("", HttpStatusCode.OK)
                },
            ) { expectSuccess = false },
        )

        val ok = KtorTaskDetailRepository(api, upload)
            .uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1, 2, 3))))

        assertTrue(ok)
        assertTrue(presignCalled, "presign was called")
        assertEquals("https://s3.example.test/bucket/att-1?sig=abc", putUrl, "PUT goes to the presigned URL")
        assertEquals(listOf<Byte>(1, 2, 3), putBytes?.toList(), "the file bytes are PUT byte-exact")
        assertEquals("aws:kms", sseHeader, "the signed SSE header is sent byte-exact")
        assertTrue(commitBody?.contains("\"att-1\"") == true, "the commit references the uploaded id")
    }

    @Test
    fun uploadAttachmentsReturnsTrueForEmptyListWithoutPresigning() = runTest {
        var anyCall = false
        val api = client { anyCall = true; respond("", HttpStatusCode.OK) }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        assertTrue(KtorTaskDetailRepository(api, upload).uploadAttachments(TaskId("t1"), emptyList()))
        assertFalse(anyCall, "an empty upload makes no network calls")
    }

    @Test
    fun uploadAttachmentsReturnsFalseWhenPresignFails() = runTest {
        val api = client { respond("", HttpStatusCode.InternalServerError) }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        assertFalse(
            KtorTaskDetailRepository(api, upload)
                .uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1)))),
        )
    }

    @Test
    fun uploadAttachmentsReturnsFalseAndDoesNotCommitWhenUploadRejected() = runTest {
        var commitCalled = false
        val api = client { request ->
            when {
                request.url.encodedPath.endsWith("/presign") -> respondJson(
                    """{"version":"0.1","data":{"attachments":[{
                       "attachment_id":"att-1","put_url":"https://s3.example.test/x","expires_at":"2026-06-12T00:00:00Z","headers":{}
                    }]}}""",
                )
                else -> { commitCalled = true; respondJson(attachmentsEnvelope, HttpStatusCode.Created) }
            }
        }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("denied", HttpStatusCode.Forbidden) }) { expectSuccess = false })

        val ok = KtorTaskDetailRepository(api, upload)
            .uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1))))

        assertFalse(ok)
        assertFalse(commitCalled, "a rejected upload aborts before the commit")
    }

    @Test
    fun uploadAttachmentsReturnsFalseWithoutAnUploadClient() = runTest {
        // Constructed with only the api client (no uploadClient) — the comment-only path.
        val repo = KtorTaskDetailRepository(client { respondJson(attachmentsEnvelope) })
        assertFalse(repo.uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1)))))
    }

    @Test
    fun deleteAttachmentHitsThePathAndReturnsTrueOn204() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respond("", HttpStatusCode.NoContent) })

        assertTrue(repo.deleteAttachment(TaskId("t1"), "att-1"))
        assertEquals(HttpMethod.Delete, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t1/attachments/att-1") == true)
    }

    @Test
    fun deleteAttachmentReturnsFalseOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.Forbidden) })
        assertFalse(repo.deleteAttachment(TaskId("t1"), "att-1"))
    }

    private val attachmentEnvelope = """
        {"version":"0.1","data":{"id":"a1","filename":"receipt.pdf","mime":"application/pdf","size":1234,
         "url":"https://files/a1","caption":"Receipt","created_by":"u1","created_at":"2026-04-17T10:00:00Z"}}
    """.trimIndent()

    @Test
    fun updateAttachmentCaptionPatchesThePathWithTheCaption() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(attachmentEnvelope) })

        assertTrue(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "Receipt"))
        assertEquals(HttpMethod.Patch, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t1/attachments/att-1") == true)
        assertTrue((captured?.body as? TextContent)?.text?.contains("Receipt") == true)
    }

    @Test
    fun updateAttachmentCaptionClearSendsExplicitNull() = runTest {
        // #416: clearing must reach the wire as `caption: null`, not an omitted field — the shared
        // DefernoJson (explicitNulls = false) would drop a null, which the server rejects as 422.
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(attachmentEnvelope) })

        assertTrue(repo.updateAttachmentCaption(TaskId("t1"), "att-1", null))
        assertEquals(HttpMethod.Patch, captured?.method)
        assertEquals("""{"caption":null}""", (captured?.body as? TextContent)?.text)
    }

    @Test
    fun updateAttachmentCaptionReturnsFalseOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.UnprocessableEntity) })
        assertFalse(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "Receipt"))
    }

    // --- test helpers ---

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        // Use the production DefernoJson so these tests exercise the real wire serialization
        // (explicitNulls = false). A default Json would mask serialization bugs like #416, where a
        // null clear is dropped on the omit-vs-null boundary.
        install(ContentNegotiation) { json(DefernoJson) }
        defaultRequest { url("https://api.example.test/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

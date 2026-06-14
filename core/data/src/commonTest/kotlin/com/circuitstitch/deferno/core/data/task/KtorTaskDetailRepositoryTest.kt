package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.UploadHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.url
import io.ktor.client.utils.EmptyContent
import io.ktor.content.TextContent
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorTaskDetailRepository] (the Task detail's online-only comments + attachments),
 * driven by Ktor's MockEngine on the JVM-fast path (ADR-0006) — no real network. Proves each call hits
 * the right path/method, maps the envelope through the DTO->domain mappers, drops soft-deleted
 * comments, and degrades to `null`/`false` on failure rather than throwing (so the detail can show an
 * inline error instead of crashing).
 */
class KtorTaskDetailRepositoryTest {

    private val commentsEnvelope = """
        {"version":"0.1","data":{"comments":[
            {"id":"c1","task_id":"t1","body":"first","created_by":"u1","created_at":"2026-04-17T10:00:00Z"},
            {"id":"c2","task_id":"t1","body":"gone","created_by":"u1","created_at":"2026-04-17T11:00:00Z",
             "deleted_at":"2026-04-17T12:00:00Z"}
        ]}}
    """.trimIndent()

    private val attachmentsEnvelope = """
        {"version":"0.1","data":[
            {"id":"a1","filename":"receipt.pdf","mime":"application/pdf","size":1234,
             "url":"https://files/a1","created_by":"u1","created_at":"2026-04-17T10:00:00Z"}
        ]}
    """.trimIndent()

    private val commentEnvelope = """
        {"version":"0.1","data":{"id":"c9","task_id":"t1","body":"posted","created_by":"u1",
         "created_at":"2026-04-17T10:00:00Z"}}
    """.trimIndent()

    private val meEnvelope = """
        {"version":"0.1","data":{"id":"u1","username":"sam","display_name":"Sam","role":"user",
         "personal_org_id":"org1","org_slug":"u-x"}}
    """.trimIndent()

    @Test
    fun commentsHitsThePathAndDropsSoftDeleted() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(commentsEnvelope) })

        val comments = repo.comments(TaskId("t1"))

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t1/comments") == true)
        assertEquals(listOf("c1"), comments?.map { it.id })
        assertEquals("first", comments?.first()?.body)
    }

    @Test
    fun commentsReturnsNullOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.InternalServerError) })
        assertNull(repo.comments(TaskId("t1")))
    }

    @Test
    fun postCommentPutsTheBodyOnTheRightPathAndMethod() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(commentEnvelope, HttpStatusCode.Created) })

        val ok = repo.postComment(TaskId("t1"), "hello")

        assertTrue(ok)
        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t1/comments") == true)
        assertTrue((captured?.body as? TextContent)?.text?.contains("hello") == true)
    }

    @Test
    fun postCommentReturnsFalseOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.BadRequest) })
        assertFalse(repo.postComment(TaskId("t1"), "hello"))
    }

    @Test
    fun editCommentPatchesTheCommentPath() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(commentEnvelope) })

        assertTrue(repo.editComment("c9", "fixed"))
        assertEquals(HttpMethod.Patch, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/comments/c9") == true)
    }

    @Test
    fun deleteCommentDeletesTheCommentPath() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(commentEnvelope) })

        assertTrue(repo.deleteComment("c9"))
        assertEquals(HttpMethod.Delete, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/comments/c9") == true)
        // A DELETE carries no request body.
        assertTrue(captured?.body is EmptyContent)
    }

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

    @Test
    fun currentUserIdResolvesFromAuthMe() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(meEnvelope) })

        assertEquals(UserId("u1"), repo.currentUserId())
        assertTrue(captured?.url?.encodedPath?.endsWith("/auth/me") == true)
    }

    @Test
    fun currentUserIdReturnsNullOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.InternalServerError) })
        assertNull(repo.currentUserId())
    }

    // --- test helpers ---

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

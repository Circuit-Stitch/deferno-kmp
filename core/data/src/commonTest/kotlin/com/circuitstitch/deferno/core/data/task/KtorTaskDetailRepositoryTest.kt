package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Behaviour of [KtorTaskDetailRepository] (the Task detail's online-only attachments — comments + item
 * history moved offline-first in ADR-0043), driven by Ktor's MockEngine on the JVM-fast path (ADR-0006) —
 * no real network. Proves each call hits the right path/method, maps the envelope through the DTO->domain
 * mappers, and degrades to `null`/`false` on failure rather than throwing.
 */
class KtorTaskDetailRepositoryTest {

    // The wire adapter never mints a stamp — it carries the one the ledger decorator handed it (#364) — so
    // every write here supplies a fixed one and the assertions can pin the exact bytes it puts on the wire.
    private val stamp = ActivityStamp("entry-1", Instant.parse("2026-04-17T10:00:00Z"))

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

        assertTrue(captured?.url?.encodedPath?.endsWith("/items/t1/attachments") == true)
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
                request.url.encodedPath.endsWith("/items/t1/attachments/presign") -> {
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
                request.url.encodedPath.endsWith("/items/t1/attachments") -> {
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

        val ok = KtorTaskDetailRepository(api, upload).uploadAttachments(
            TaskId("t1"),
            listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1, 2, 3))),
            stamp,
        )

        assertTrue(ok)
        assertTrue(presignCalled, "presign was called")
        assertEquals("https://s3.example.test/bucket/att-1?sig=abc", putUrl, "PUT goes to the presigned URL")
        assertEquals(listOf<Byte>(1, 2, 3), putBytes?.toList(), "the file bytes are PUT byte-exact")
        assertEquals("aws:kms", sseHeader, "the signed SSE header is sent byte-exact")

        // The commit's IntentEntry key is `id` — NOT the `attachment_id` the presign RESPONSE uses. A
        // substring check for "att-1" passes either way, which is how a body the server parses as zero
        // intents shipped once: the bytes reach S3, the commit succeeds, and the attachment never exists.
        val body = assertNotNull(commitBody, "the commit was sent")
        val intent = DefernoJson.parseToJsonElement(body).jsonObject.getValue("intents").jsonArray.single().jsonObject
        assertEquals(listOf("id"), intent.keys.toList(), "the intent carries exactly the contract's `id` key")
        assertEquals("att-1", intent.getValue("id").jsonPrimitive.content)
        assertFalse(body.contains("attachment_id"), "the presign response's key never appears on the commit")
        // The whole commit body, pinned: the intent list plus the stamp as its sibling and nothing else. A
        // new defaulted field on CommitAttachmentsPayload breaks this deliberately — whether it belongs on
        // the wire is a decision to make, not a detail to absorb into a looser `contains` check.
        assertEquals(
            """{"intents":[{"id":"att-1"}],"activity":{"id":"entry-1","at":"2026-04-17T10:00:00Z","source":"mobile"}}""",
            body,
        )
    }

    @Test
    fun theUploadCommitCarriesTheStampAndThePresignHandshakeDoesNot() = runTest {
        var presignBody: String? = null
        var commitBody: String? = null
        val api = client { request ->
            when {
                request.url.encodedPath.endsWith("/presign") -> {
                    presignBody = (request.body as? TextContent)?.text
                    respondJson(
                        """{"version":"0.1","data":{"attachments":[{
                           "attachment_id":"att-1","put_url":"https://s3.example.test/x",
                           "expires_at":"2026-06-12T00:00:00Z","headers":{}
                        }]}}""",
                    )
                }
                else -> {
                    commitBody = (request.body as? TextContent)?.text
                    respondJson(attachmentsEnvelope, HttpStatusCode.Created)
                }
            }
        }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        val ok = KtorTaskDetailRepository(api, upload).uploadAttachments(
            TaskId("t1"),
            listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1))),
            stamp,
        )

        assertTrue(ok)
        // Presign is a handshake, not an item mutation — it mints no ledger row, and its payload is strict:
        // an unexpected `activity` key would 422 and sink the upload before a single byte moved.
        assertFalse(presignBody?.contains("activity") == true, "the presign handshake carries no stamp")

        val body = assertNotNull(commitBody, "the commit was sent")
        val activity = DefernoJson.parseToJsonElement(body).jsonObject.getValue("activity").jsonObject
        // The entry id is the merge key the `?since=` reconcile dedupes on, so it must reach the server
        // verbatim; `at` is the client wall-clock the feed sorts by, not the server's receive time.
        assertEquals("entry-1", activity.getValue("id").jsonPrimitive.content)
        assertEquals("2026-04-17T10:00:00Z", activity.getValue("at").jsonPrimitive.content)
        assertEquals("mobile", activity.getValue("source").jsonPrimitive.content)
        // The stamp is a sibling of `intents`, not a replacement for it.
        assertEquals(
            "att-1",
            DefernoJson.parseToJsonElement(body).jsonObject.getValue("intents")
                .jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun uploadAttachmentsReturnsTrueForEmptyListWithoutPresigning() = runTest {
        var anyCall = false
        val api = client { anyCall = true; respond("", HttpStatusCode.OK) }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        assertTrue(KtorTaskDetailRepository(api, upload).uploadAttachments(TaskId("t1"), emptyList(), stamp))
        assertFalse(anyCall, "an empty upload makes no network calls")
    }

    @Test
    fun uploadAttachmentsReturnsFalseWhenPresignFails() = runTest {
        val api = client { respond("", HttpStatusCode.InternalServerError) }
        val upload = UploadHttpClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false })

        assertFalse(
            KtorTaskDetailRepository(api, upload).uploadAttachments(
                TaskId("t1"),
                listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1))),
                stamp,
            ),
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

        val ok = KtorTaskDetailRepository(api, upload).uploadAttachments(
            TaskId("t1"),
            listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1))),
            stamp,
        )

        assertFalse(ok)
        assertFalse(commitCalled, "a rejected upload aborts before the commit")
    }

    @Test
    fun uploadAttachmentsReturnsFalseWithoutAnUploadClient() = runTest {
        // Constructed with only the api client (no uploadClient) — there is nothing to PUT the bytes with,
        // so the upload must fail outright rather than commit an intent whose object never reached S3.
        val repo = KtorTaskDetailRepository(client { respondJson(attachmentsEnvelope) })
        assertFalse(
            repo.uploadAttachments(
                TaskId("t1"),
                listOf(AttachmentUpload("r.pdf", "application/pdf", byteArrayOf(1))),
                stamp,
            ),
        )
    }

    @Test
    fun deleteAttachmentPostsToTheDeleteSubresourceAndReturnsTrueOn204() = runTest {
        // #364: `DELETE /tasks/{id}/attachments/{aid}` is gone twice over — the surface became
        // kind-neutral (/items/...) AND the delete became a POST soft-delete so it can carry the
        // client-minted Activity stamp in a body. No alias exists for either old form.
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respond("", HttpStatusCode.NoContent) })

        assertTrue(repo.deleteAttachment(TaskId("t1"), "att-1", stamp))
        assertEquals(HttpMethod.Post, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/items/t1/attachments/att-1/delete") == true)
    }

    @Test
    fun deleteAttachmentReturnsFalseOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.Forbidden) })
        assertFalse(repo.deleteAttachment(TaskId("t1"), "att-1", stamp))
    }

    @Test
    fun deleteAttachmentSendsTheStampAsItsWholeBody() = runTest {
        // The route's body is `ActivityBody` — every field optional server-side, so `{}` would parse — but
        // the client never sends that shape: a delete the server stamps itself lands under an entry id the
        // `?since=` reconcile can never match to the local row. With one mandatory field there is nothing
        // for a hand-rolled body to get wrong, which is why this one stays a buildJsonObject.
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respond("", HttpStatusCode.NoContent) })

        assertTrue(repo.deleteAttachment(TaskId("t1"), "att-1", stamp))
        assertEquals(
            """{"activity":{"id":"entry-1","at":"2026-04-17T10:00:00Z","source":"mobile"}}""",
            (captured?.body as? TextContent)?.text,
        )
    }

    private val attachmentEnvelope = """
        {"version":"0.1","data":{"id":"a1","filename":"receipt.pdf","mime":"application/pdf","size":1234,
         "url":"https://files/a1","caption":"Receipt","created_by":"u1","created_at":"2026-04-17T10:00:00Z"}}
    """.trimIndent()

    @Test
    fun updateAttachmentCaptionPatchesThePathWithTheCaption() = runTest {
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(attachmentEnvelope) })

        assertTrue(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "Receipt", stamp))
        assertEquals(HttpMethod.Patch, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/items/t1/attachments/att-1") == true)
        assertTrue((captured?.body as? TextContent)?.text?.contains("Receipt") == true)
    }

    @Test
    fun updateAttachmentCaptionClearSendsExplicitNullBesideTheStamp() = runTest {
        // #416: clearing must reach the wire as `caption: null`, not an omitted field — the shared
        // DefernoJson (explicitNulls = false) would drop a null, which the server rejects as 422. That is
        // also why this one body stays hand-built: no typed payload can say "send caption as an explicit
        // null" and "carry the stamp's own object" under a single Json config — explicitNulls governs both.
        var captured: HttpRequestData? = null
        val repo = KtorTaskDetailRepository(client { req -> captured = req; respondJson(attachmentEnvelope) })

        assertTrue(repo.updateAttachmentCaption(TaskId("t1"), "att-1", null, stamp))
        assertEquals(HttpMethod.Patch, captured?.method)
        assertEquals(
            """{"caption":null,"activity":{"id":"entry-1","at":"2026-04-17T10:00:00Z","source":"mobile"}}""",
            (captured?.body as? TextContent)?.text,
        )
    }

    @Test
    fun updateAttachmentCaptionReturnsFalseOnFailure() = runTest {
        val repo = KtorTaskDetailRepository(client { respond("", HttpStatusCode.UnprocessableEntity) })
        assertFalse(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "Receipt", stamp))
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

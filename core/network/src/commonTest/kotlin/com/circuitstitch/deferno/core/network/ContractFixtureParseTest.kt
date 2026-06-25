package com.circuitstitch.deferno.core.network

import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto
import com.circuitstitch.deferno.core.network.dto.DefStatusWire
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import com.circuitstitch.deferno.core.network.dto.TodayTaskDto
import com.circuitstitch.deferno.core.network.dto.UserSettingsDto
import com.circuitstitch.deferno.core.network.fixtures.ContractFixtures
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The golden-envelope contract-fixture harness (#19, ADR-0006). Each representative API envelope
 * captured from staging (`contracts/fixtures/`) is fed through the SHIPPING read path — the tolerant
 * reader, envelope unwrap, and version gate in [requestApi] / `toApiResult` over a Ktor `MockEngine`
 * — and asserted against the canonical wire DTOs. (The one exception is the [TaskDetailDto] check,
 * which decodes a single element sliced out of the captured `/items` list through the same tolerant
 * reader rather than a full envelope — `/items` returns the task inside a polymorphic list.)
 *
 * The fixtures are **loaded**, not hand-authored: [ContractFixtures] is generated from the captured
 * `.json` files under `contracts/fixtures/` at build time (`deferno.contract-fixtures`), so a re-capture
 * (`contracts/refresh.sh` for the spec; the documented capture for the fixtures) regenerates the
 * embedded constants and a breaking backend shape change surfaces here as a failing parse rather than
 * a silent miss ("capture, don't hand-author" — `contracts/fixtures/README.md`).
 */
class ContractFixtureParseTest {

    // --- auth-me.json → Envelope<AuthenticatedUserDto> ---

    @Test
    fun authMeParsesIntoAuthenticatedUser() = runTest {
        val user = parseData<AuthenticatedUserDto>("auth-me.json")

        assertEquals("1d35f62e-eed9-44de-96e8-e61a307af83f", user.id)
        assertEquals("sampleuser", user.username)
        assertEquals("Sample User", user.displayName)
        assertEquals("admin", user.role)
        assertEquals("ebca93e5-d663-4624-9fe9-c5361b5b4390", user.personalOrgId)
        assertEquals("u-e4h2qk", user.orgSlug)
        assertEquals(false, user.isAdmin)
        assertEquals("https://auth2.defernowork.com/ui/console", user.consoleUrl)
    }

    // --- settings.json → Envelope<UserSettingsDto> ---

    @Test
    fun settingsParsesIntoUserSettings() = runTest {
        val settings = parseData<UserSettingsDto>("settings.json")

        assertEquals(259200L, settings.globalDoneVisibilitySeconds)
        assertEquals(86400L, settings.dashboardDoneVisibilitySeconds)
        assertEquals("deferno", settings.themeFamily)
        assertEquals("light", settings.themeMode)
        assertEquals("America/Los_Angeles", settings.timeZone)
        assertTrue(settings.trackingEnabled)
        assertTrue(settings.dragAndDropEnabled)
    }

    // --- tasks-sample.json → Envelope<List<TaskSummaryDto>> (the /tasks list shape) ---

    @Test
    fun tasksSampleParsesIntoTaskSummaries() = runTest {
        val tasks = parseData<List<TaskSummaryDto>>("tasks-sample.json")
        assertEquals(3, tasks.size)

        val first = tasks[0]
        assertEquals("7033cae7-eff6-4df1-bed9-01d16e89c2b0", first.id)
        assertEquals(TaskStatusWire.Dropped, first.status)
        assertEquals(listOf("family"), first.labels)
        assertNull(first.parentId)
        assertEquals("2026-04-10T07:45:00Z", first.completeBy)
        assertEquals(-0.45, first.productive)
        assertNull(first.desire)
        assertEquals("u-e4h2qk-1", first.ref)
        assertEquals(1L, first.sequence)
        assertEquals("task", first.type)
        assertNull(first.deletedAt)

        // The descendant roll-up rides on the summary.
        assertEquals(2, tasks[1].children.size)
        assertEquals(1, tasks[1].descendantDone)
        assertEquals(2, tasks[1].descendantTotal)

        assertEquals(TaskStatusWire.Open, tasks[2].status)
        assertTrue(tasks[2].pinned)
    }

    // --- plan.json → Envelope<List<TaskSummaryDto>>, incl. brand-new rows that omit ref+sequence ---

    @Test
    fun planParsesIncludingBrandNewRowsThatOmitRefAndSequence() = runTest {
        val plan = parseData<List<TaskSummaryDto>>("plan.json")
        assertEquals(3, plan.size)

        val withRef = plan[0]
        assertEquals("u-e4h2qk-90", withRef.ref)
        assertEquals(90L, withRef.sequence)
        assertEquals("bffc4836-5899-4c47-abcc-90181bff5d68", withRef.parentId)

        // Brand-new server-seeded rows OMIT ref + sequence on the wire → nullable / default; their
        // org_slug is still present. (The faithful capture has more of these than the spec implies.)
        plan.drop(1).forEach { row ->
            assertNull(row.ref)
            assertNull(row.sequence)
            assertEquals("u-e4h2qk", row.orgSlug)
        }
    }

    // --- today-sample.json → Envelope<List<TodayTaskDto>> (the nested `task` shape) ---

    @Test
    fun todaySampleParsesNestedTaskWithPriority() = runTest {
        val today = parseData<List<TodayTaskDto>>("today-sample.json")
        assertEquals(2, today.size)

        val first = today[0]
        assertEquals("7ac8513c-4037-43fe-a89a-721019f76527", first.task.id)
        assertEquals(TaskStatusWire.Open, first.task.status)
        assertEquals(55.0, first.priorityScore)
        assertEquals("overdue", first.urgencyReason)
        assertEquals("u-e4h2qk-123", first.ref)
        assertEquals(123L, first.sequence)
        // The wrapper carries org_slug; the nested summary omits it, so the today mapper must read it
        // from here (TaskSummaryDto KDoc) — assert it so a mis-mapped SerialName would be caught.
        assertEquals("u-e4h2qk", first.orgSlug)
        // The nested summary omits ref/sequence — they live on the envelope wrapper.
        assertNull(first.task.ref)
        assertNull(first.task.sequence)
    }

    // --- items-sample.json → Envelope<List<ItemView>> across all four sealed kinds ---

    @Test
    fun itemsSampleParsesIntoTheFourSealedKinds() = runTest {
        val items = parseData<List<ItemView>>("items-sample.json")
        assertEquals(4, items.size)

        val task = assertIs<ItemView.Task>(items[0])
        assertEquals("948bcfab-063d-4499-b2de-f21801bc6f9c", task.id)
        assertEquals(TaskStatusWire.Open, task.status)
        assertTrue(task.pinned)
        // Server-derived dependency state (#289): the task is blocked, by one edge (occurrence absent).
        assertTrue(task.blocked)
        assertEquals(false, task.isBlocker)
        assertEquals("77dd6a6e-b936-4f61-9807-c3a6b647f9f1", task.blockedBy.single().item)
        assertNull(task.blockedBy.single().occurrence)

        val habit = assertIs<ItemView.Habit>(items[1])
        assertEquals("77dd6a6e-b936-4f61-9807-c3a6b647f9f1", habit.id)
        assertEquals(DefStatusWire.Active, habit.status)
        assertEquals("b7c21959-c5f6-4087-8ab2-7690c81e463a", habit.seriesId)
        assertEquals("daily", habit.recurrence?.type)
        // A recurring kind decodes isBlocker too (it gates the task above).
        assertTrue(habit.isBlocker)
        assertEquals(false, habit.blocked)

        val chore = assertIs<ItemView.Chore>(items[2])
        assertEquals("47338a14-a07f-4ddf-ad73-f5edc977dab0", chore.id)
        assertEquals("rolling", chore.cadenceMode)
        assertEquals(listOf("Tue"), chore.recurrence?.days)
        // The chore/event elements omit the dependency fields → they default false (absent case).
        assertEquals(false, chore.blocked)
        assertEquals(false, chore.isBlocker)

        val event = assertIs<ItemView.Event>(items[3])
        assertEquals("d4f26212-07ac-4ebc-b5d9-fe4649a69a3e", event.id)
        assertEquals(false, event.allDay)
        assertEquals("2026-04-18T17:30:00Z", event.endTime)
        assertEquals(false, event.blocked)
        assertEquals(false, event.isBlocker)
    }

    // --- items-sample.json: the captured `task` element is also the faithful /tasks/{id} detail ---

    @Test
    fun itemsSampleTaskElementAlsoParsesAsTaskDetail() = runTest {
        // `/items` and `/tasks/{id}` return the SAME full `task` shape; the captured /items task
        // element is therefore the faithful detail payload. Slice it from the REAL fixture (not a
        // hand-authored envelope) and decode through [TaskDetailDto] to assert the full-only
        // enrichment the summary lacks (owner_org_id, description, next_task_id, finished_at).
        val data = DefernoJson.decodeFromString(
            Envelope.serializer(ListSerializer(JsonObject.serializer())),
            ContractFixtures.ALL.getValue("items-sample.json"),
        ).data
        val taskElement = data.first { it["type"]?.jsonPrimitive?.content == "task" }
        val detail = DefernoJson.decodeFromJsonElement(TaskDetailDto.serializer(), taskElement)

        assertEquals("948bcfab-063d-4499-b2de-f21801bc6f9c", detail.id)
        assertEquals("u-e4h2qk", detail.orgSlug)
        assertEquals("ebca93e5-d663-4624-9fe9-c5361b5b4390", detail.ownerOrgId)
        assertEquals("u-e4h2qk-311", detail.ref)
        assertEquals(311L, detail.sequence)
        assertEquals(TaskStatusWire.Open, detail.status)
        assertEquals(2, detail.children.size)
        assertEquals("<description>", detail.description)
        assertNull(detail.nextTaskId)
        assertNull(detail.finishedAt)
        assertNull(detail.deletedAt)
        assertEquals("task", detail.type)
        // The full single-item record carries the ordered blockedBy edge list (#289).
        assertTrue(detail.blocked)
        assertEquals(false, detail.isBlocker)
        assertEquals("77dd6a6e-b936-4f61-9807-c3a6b647f9f1", detail.blockedBy.single().item)
    }

    // --- error-404.json → ApiError.Endpoint via the shipping error path ---

    @Test
    fun error404ParsesIntoEndpointFailure() = runTest {
        // The captured error envelope flows through `toApiResult`'s present-body branch → Endpoint.
        val result = fixtureClient("error-404.json", HttpStatusCode.NotFound).requestApi<TaskSummaryDto>()

        assertEquals(
            ApiResult.Failure(ApiError.Endpoint(status = 404, code = "not_found", message = "task not found")),
            result,
        )
    }

    // --- the empty-401 negative case: NO fixture file by design ---

    @Test
    fun empty401SynthesizesStatusFromTheHttpStatus() = runTest {
        // An invalid/expired bearer returns HTTP 401 with an EMPTY body — not an ErrorEnvelope
        // (verified; contracts/CONTRACT-NOTES.md → "Error model"). There is deliberately no fixture
        // file (contracts/fixtures/README.md): the reader MUST synthesize the failure from the HTTP
        // status alone when the body is absent. Kept here, co-located with the captured fixtures, as
        // the documented negative counterpart to error-404 — it deliberately overlaps the behavioural
        // check in DefernoHttpClientTest. (The `message` is Ktor's canonical 401 reason phrase; the
        // load-bearing assertions are the Status *type* — not Endpoint — and the 401 status code.)
        val result = clientReturning("", HttpStatusCode.Unauthorized).requestApi<TaskSummaryDto>()

        assertEquals(ApiResult.Failure(ApiError.Status(status = 401, message = "Unauthorized")), result)
    }

    // --- completeness guard: every captured fixture has a wired parse handler ---

    @Test
    fun everyCapturedFixtureHasAWiredParseHandler() = runTest {
        // Drives one parse per captured fixture straight off the generated [ContractFixtures.ALL], so a
        // newly-captured fixture with no wired handler fails the build *here* (`else -> fail`) — the
        // real "no silently-uncovered fixture" guard (a removal shrinks ALL and breaks its per-fixture
        // test instead). The per-fixture @Test methods above own the deep, field-by-field assertions;
        // this only proves every captured fixture flows through a handler. A plain `for` loop (not
        // `forEach`) so the suspend parse calls are legal in the loop body.
        for (name in ContractFixtures.ALL.keys) {
            when (name) {
                "auth-me.json" -> parseData<AuthenticatedUserDto>(name)
                "settings.json" -> parseData<UserSettingsDto>(name)
                "tasks-sample.json" -> parseData<List<TaskSummaryDto>>(name)
                "plan.json" -> parseData<List<TaskSummaryDto>>(name)
                "today-sample.json" -> parseData<List<TodayTaskDto>>(name)
                "items-sample.json" -> parseData<List<ItemView>>(name)
                "error-404.json" -> {
                    val result = fixtureClient(name, HttpStatusCode.NotFound).requestApi<TaskSummaryDto>()
                    assertIs<ApiError.Endpoint>(assertIs<ApiResult.Failure>(result).error)
                }
                else -> fail(
                    "No parse handler wired for captured fixture '$name' — add one to " +
                        "ContractFixtureParseTest (capture, don't hand-author).",
                )
            }
        }
    }

    // --- helpers ---

    /** Feeds [fixture]'s captured body through the shipping 2xx read path and returns the payload. */
    private suspend inline fun <reified T> parseData(fixture: String): T {
        val result = fixtureClient(fixture).requestApi<T>()
        if (result !is ApiResult.Success) {
            fail("fixture '$fixture' should parse to Success but was: $result")
        }
        return result.data
    }

    private fun fixtureClient(fixture: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        clientReturning(ContractFixtures.ALL.getValue(fixture), status)

    private fun clientReturning(body: String, status: HttpStatusCode): HttpClient =
        defernoHttpClient(
            MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
            DefernoEnvironment.Production,
            BearerTokenProvider { null },
        )
}

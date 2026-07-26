package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.ClearDeadline
import com.circuitstitch.deferno.core.data.outbox.DeleteComment
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.Rename
import com.circuitstitch.deferno.core.data.outbox.SetTheme
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Activity-ingest stamp (#364): how it is merged onto a mutation body, and its wire shape.
 *
 * *Which* routes carry one is no longer asked here — it is declared at the route by whoever picked it
 * ([OutboxRequest.acceptsActivityStamp]) and asserted beside that route's own request assertion in
 * `MutationTest` / `OccurrenceMutationTest` / `CommentMutationTest` / `CreateMutationTest` /
 * `SettingsMutationTest`. So a mutation that changes its endpoint drags its stamp declaration along,
 * instead of leaving a hand-typed path in a far-away whitelist test quietly asserting the old shape.
 *
 * What is left is the merge, and its failure modes are all silent: dropping an explicit JSON null turns a
 * field-clear into a no-op, throwing on a malformed body takes out the user's write on its way to the
 * durable queue, and a mis-rendered `at` reorders offline work in the server's feed.
 */
class ActivityStampTest {

    private val stamp = ActivityStamp("entry-1", Instant.parse("2026-07-24T09:15:30Z"))
    private val stampJson = """{"id":"entry-1","at":"2026-07-24T09:15:30Z","source":"mobile"}"""

    /** A stamp-accepting route, so each test below isolates the merge rather than the opt-in. */
    private fun request(method: OutboxMethod, vararg path: String, body: String? = "{}") =
        OutboxRequest(method, path.toList(), body, acceptsActivityStamp = true)

    // --- merging ---------------------------------------------------------------------------------

    @Test
    fun theStampIsMergedAsASiblingWithoutDisturbingTheExistingKeys() {
        // ADR-0011's minimal body must survive verbatim: the merge only ever *adds* `activity`, and the
        // original keys keep their values and their order (the outbox replays these bytes as-is).
        val stamped = Rename(TaskId("t-1"), "New title").toRequest().withActivityStamp(stamp)
        assertEquals("""{"title":"New title","activity":$stampJson}""", stamped.body)
        // Only the body changes — the route the outbox replays to is untouched.
        assertEquals(OutboxMethod.Patch, stamped.method)
        assertEquals(listOf("tasks", "t-1"), stamped.path)
    }

    @Test
    fun anExplicitJsonNullSurvivesTheMergeVerbatim() {
        // `{"complete_by":null}` is the "clear this field" intent (ADR-0011) and is NOT interchangeable
        // with an absent key — dropping or re-encoding it would turn a deadline-clear into a no-op.
        val stamped = ClearDeadline(TaskId("t-1")).toRequest().withActivityStamp(stamp)
        assertEquals("""{"complete_by":null,"activity":$stampJson}""", stamped.body)
    }

    @Test
    fun anEmptyObjectBodyBecomesTheStampAlone() {
        // The `{}` bodies ClearOccurrence / DeleteComment render exist precisely so the stamp has
        // somewhere to land — a null body would send no HTTP entity at all.
        assertEquals("""{"activity":$stampJson}""", DeleteComment("t-1", "c-1").toRequest().withActivityStamp(stamp).body)
    }

    @Test
    fun mergingIsANoOpOnARouteThatDidNotDeclareItAcceptsAStamp() {
        // Belt-and-braces with the choke-point: even if a caller stamps unconditionally, a route that
        // never opted in goes out byte-identical rather than 422-ing and dead-lettering the user's write.
        val settings = SetTheme(ThemeFamily.Mono, ThemeMode.Dark).toRequest()
        assertEquals(false, settings.acceptsActivityStamp)
        assertSame(settings, settings.withActivityStamp(stamp))
    }

    @Test
    fun mergingIsANoOpWhenTheRequestHasNoBody() {
        // A null body sends no HTTP entity, so there is nothing to merge into — and inventing one would
        // turn a bodiless route into a bodied request. The route may well accept a stamp; this request
        // simply has nowhere to put one.
        val convert = request(OutboxMethod.Post, "items", "i-1", "convert", body = null)
        assertTrue(convert.acceptsActivityStamp)
        assertSame(convert, convert.withActivityStamp(stamp))
    }

    @Test
    fun aBodyThatIsNotAJsonObjectIsReturnedUnchangedRatherThanThrowing() {
        // Nothing renders these today, but the merge runs on every enqueue: a throw here would take out
        // the user's write on the way to a durable queue. Return the request untouched instead.
        for (body in listOf("[1,2]", "\"just a string\"", "42", "null", "{not json", "")) {
            val original = request(OutboxMethod.Patch, "tasks", "t-1", body = body)
            assertSame(original, original.withActivityStamp(stamp), "body <$body> must pass through unchanged")
        }
    }

    // --- the wire object -------------------------------------------------------------------------

    @Test
    fun toJsonEmitsTheEntryIdAnRfc3339InstantAndTheMobileSource() {
        // These three key names are the ingest contract; `at` is the client wall-clock the feed sorts by,
        // so a non-RFC-3339 rendering would reorder (or reject) offline work on the server.
        assertEquals(stampJson, stamp.toJson().toString())
        assertEquals(
            """{"id":"e-2","at":"2026-07-24T09:15:30.250Z","source":"mobile"}""",
            ActivityStamp("e-2", Instant.parse("2026-07-24T09:15:30.250Z")).toJson().toString(),
        )
    }

    @Test
    fun mintKeepsTheSuppliedInstantAndGivesEachActionItsOwnMergeKey() {
        val at = Instant.parse("2026-07-24T09:15:30Z")
        val first = ActivityStamp.mint(at)
        assertEquals(at, first.occurredAt)
        // The entry id is the merge key between the optimistic row and the server's authoritative one, so
        // two distinct actions must never collide — a shared id would make the reconcile replace one with
        // the other and drop a row from the feed.
        assertNotEquals(first.entryId, ActivityStamp.mint(at).entryId)
    }
}

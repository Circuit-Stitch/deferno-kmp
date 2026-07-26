package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.ClearDeadline
import com.circuitstitch.deferno.core.data.outbox.ClearOccurrence
import com.circuitstitch.deferno.core.data.outbox.CreateChoreItem
import com.circuitstitch.deferno.core.data.outbox.CreateEventItem
import com.circuitstitch.deferno.core.data.outbox.CreateHabitItem
import com.circuitstitch.deferno.core.data.outbox.CreateTaskItem
import com.circuitstitch.deferno.core.data.outbox.DeleteComment
import com.circuitstitch.deferno.core.data.outbox.DeleteTask
import com.circuitstitch.deferno.core.data.outbox.EditComment
import com.circuitstitch.deferno.core.data.outbox.MarkOccurrence
import com.circuitstitch.deferno.core.data.outbox.Move
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.PlanAdd
import com.circuitstitch.deferno.core.data.outbox.PlanRemove
import com.circuitstitch.deferno.core.data.outbox.PlanReorder
import com.circuitstitch.deferno.core.data.outbox.PostComment
import com.circuitstitch.deferno.core.data.outbox.Rename
import com.circuitstitch.deferno.core.data.outbox.RescheduleOccurrence
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionState
import com.circuitstitch.deferno.core.data.outbox.SetTheme
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Activity-ingest stamp (#364): which routes may carry an `activity` sibling, and how it is merged.
 *
 * **The negative cases are the load-bearing ones.** [acceptsActivityStamp] is a whitelist, and a false
 * positive sends an unexpected key to a route with a strict payload — a `422`, which
 * [com.circuitstitch.deferno.core.data.outbox.KtorOutboxRequestSender] classifies as Terminal and the
 * processor then **dead-letters**. So a whitelist that is one segment too loose does not lose an audit
 * row, it destroys the user's write. A false *negative* costs only a server-minted entry id. Hence the
 * default-reject test below, which pins the routes that look adjacent to a stamped one but aren't.
 *
 * Routes are driven off the real [com.circuitstitch.deferno.core.data.outbox.Mutation]s wherever one
 * exists, so a mutation that changes its endpoint drags this test with it rather than leaving a
 * hand-typed path quietly asserting the old shape.
 */
class ActivityStampTest {

    private val stamp = ActivityStamp("entry-1", Instant.parse("2026-07-24T09:15:30Z"))
    private val stampJson = """{"id":"entry-1","at":"2026-07-24T09:15:30Z","source":"mobile"}"""

    private val date = LocalDate(2026, 7, 24)
    private val daily = RecurrenceDto(type = "daily")

    private fun request(method: OutboxMethod, vararg path: String, body: String? = "{}") =
        OutboxRequest(method, path.toList(), body)

    // --- accepted routes -------------------------------------------------------------------------

    @Test
    fun everyKindsCreateAcceptsAStamp() {
        // The create bodies already carry a client-supplied `id`; `activity` rides alongside it.
        assertTrue(CreateTaskItem("i-1", CreateTaskPayload(title = "T")).toRequest().acceptsActivityStamp())
        assertTrue(CreateHabitItem("i-2", CreateHabitPayload(title = "H", recurrence = daily)).toRequest().acceptsActivityStamp())
        assertTrue(CreateChoreItem("i-3", CreateChorePayload(title = "C", recurrence = daily)).toRequest().acceptsActivityStamp())
        assertTrue(
            CreateEventItem("i-4", CreateEventPayload(title = "E", completeBy = "2026-07-24"))
                .toRequest().acceptsActivityStamp(),
        )
    }

    @Test
    fun itemFieldEditsAcceptAStampOnEveryKindScopedPatch() {
        assertTrue(Rename(TaskId("t-1"), "New title").toRequest().acceptsActivityStamp())
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(
                SetDefinitionState("d-1", kind, DefinitionState.Archived).toRequest().acceptsActivityStamp(),
                "PATCH for $kind must accept a stamp",
            )
        }
    }

    @Test
    fun planAddRemoveAndReorderAcceptAStamp() {
        assertTrue(PlanAdd(TaskId("t-1"), date, "UTC").toRequest().acceptsActivityStamp())
        assertTrue(PlanRemove(TaskId("t-1"), date, "UTC").toRequest().acceptsActivityStamp())
        assertTrue(PlanReorder(listOf(TaskId("t-1")), date, "UTC").toRequest().acceptsActivityStamp())
    }

    @Test
    fun moveAcceptsAStampUnderBothTheItemsAndTheTasksPrefix() {
        assertTrue(Move("i-1", newParentId = null, position = 0).toRequest().acceptsActivityStamp())
        // The tasks-prefixed alias is in the pinned contract too — no Mutation renders it today, so it
        // would silently lose its stamp if the whitelist narrowed to `items` alone.
        assertTrue(request(OutboxMethod.Post, "tasks", "t-1", "move").acceptsActivityStamp())
    }

    @Test
    fun convertAcceptsAStamp() {
        // Mirrors the request OfflineCreateWriter records for `POST items/{id}/convert`.
        assertTrue(request(OutboxMethod.Post, "items", "i-1", "convert").acceptsActivityStamp())
    }

    @Test
    fun commentPostEditAndDeleteAllAcceptAStamp() {
        assertTrue(PostComment(TaskId("t-1"), clientId = "c-client", body = "hi").toRequest().acceptsActivityStamp())
        assertTrue(EditComment(taskId = "t-1", commentId = "c-1", body = "edited").toRequest().acceptsActivityStamp())
        // The #364 POST soft-delete, not the retired `DELETE comments/{id}`.
        assertTrue(DeleteComment(taskId = "t-1", commentId = "c-1").toRequest().acceptsActivityStamp())
    }

    @Test
    fun theThreeAttachmentMutationsAcceptAStamp() {
        // Paths mirror KtorTaskDetailRepository / LedgerRecordingTaskDetailRepository.
        assertTrue(request(OutboxMethod.Post, "items", "t-1", "attachments").acceptsActivityStamp()) // commit
        assertTrue(request(OutboxMethod.Patch, "items", "t-1", "attachments", "a-1").acceptsActivityStamp()) // caption
        assertTrue(request(OutboxMethod.Post, "items", "t-1", "attachments", "a-1", "delete").acceptsActivityStamp())
    }

    @Test
    fun occurrenceMarksAcceptAStampAcrossTheThreeKindSpecificVerbsAndShapes() {
        // Each recurring kind marks through a differently-shaped route; all three must be reachable, so a
        // whitelist written against only the habit shape would strand chore + event marks.
        val habit = MarkOccurrence("ce-1", ItemKind.Habit, "h-1", date, OccurrenceAction.Complete).toRequest()
        assertEquals(listOf("habits", "h-1", "occurrences"), habit.path)
        assertTrue(habit.acceptsActivityStamp())

        val chore = MarkOccurrence("ce-1", ItemKind.Chore, "c-1", date, OccurrenceAction.Start).toRequest()
        assertEquals(OutboxMethod.Put, chore.method)
        assertTrue(chore.acceptsActivityStamp())

        val event = MarkOccurrence("ce-1", ItemKind.Event, "e-1", date, OccurrenceAction.Complete).toRequest()
        assertEquals(listOf("events", "e-1", "occurrences", "2026-07-24"), event.path)
        assertTrue(event.acceptsActivityStamp())
    }

    @Test
    fun occurrenceClearAndRescheduleSubresourcesAcceptAStamp() {
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(
                ClearOccurrence("ce-1", kind, "s-1", date).toRequest().acceptsActivityStamp(),
                "clear for $kind must accept a stamp",
            )
            assertTrue(
                RescheduleOccurrence("ce-1", kind, "s-1", date, LocalDate(2026, 7, 26))
                    .toRequest().acceptsActivityStamp(),
                "reschedule for $kind must accept a stamp",
            )
        }
    }

    // --- rejected routes: a false positive here dead-letters the write --------------------------

    @Test
    fun theSettingsPatchIsRejectedBecauseItIsNotAnItemMutation() {
        // `PATCH auth/me/settings` has a strict user-preferences payload and mints no ledger entry. A
        // stamp here 422s, the sender calls that Terminal, and the processor dead-letters — the user's
        // theme/tracking change is silently thrown away rather than merely un-audited.
        val settings = SetTheme(ThemeFamily.Mono, ThemeMode.Dark).toRequest()
        assertEquals(listOf("auth", "me", "settings"), settings.path)
        assertFalse(settings.acceptsActivityStamp())
    }

    @Test
    fun bodilessItemDeletesAreRejectedBecauseTheyHaveNoBodyToCarryOne() {
        // The ADR's soft-delete migration covered comments/attachments/occurrence-clears but NOT item
        // delete: `DELETE tasks/{id}` and `DELETE items/{id}` are still bodiless upstream. Whitelisting
        // either would push a key onto a route that accepts no entity — a 422, i.e. a dead-lettered
        // delete, leaving the row un-deleted on the server while the UI shows it gone.
        val deleteTask = DeleteTask(TaskId("t-1"), Instant.parse("2026-07-24T09:15:30Z")).toRequest()
        assertEquals(OutboxMethod.Delete, deleteTask.method)
        assertFalse(deleteTask.acceptsActivityStamp())
        assertFalse(request(OutboxMethod.Delete, "items", "i-1", body = null).acceptsActivityStamp())
    }

    @Test
    fun theAttachmentPresignHandshakeIsRejectedEvenThoughItsSiblingsAreNot() {
        // presign is a handshake that mints no ledger entry server-side — it only vends upload URLs; the
        // ledger row belongs to the *commit* that follows. It sits one segment away from the whitelisted
        // `POST items/{id}/attachments` commit, so this is the likeliest place for the whitelist to
        // over-reach — and a 422 there dead-letters the upload before a single byte moves.
        assertFalse(request(OutboxMethod.Post, "items", "t-1", "attachments", "presign").acceptsActivityStamp())
    }

    @Test
    fun routesAdjacentToAStampedOneStillDefaultToReject() {
        // Each of these differs from a whitelisted route by one segment, one verb or one subresource name.
        // The whitelist must reject on no-match rather than pattern-match loosely — again, because the
        // failure mode is a dead-lettered write, not a missing audit row.
        assertFalse(request(OutboxMethod.Post, "tasks", "plan").acceptsActivityStamp()) // not an action leaf
        assertFalse(request(OutboxMethod.Patch, "tasks").acceptsActivityStamp()) // collection, not an item
        assertFalse(request(OutboxMethod.Post, "tasks", "t-1", "comments", "c-1").acceptsActivityStamp())
        assertFalse(request(OutboxMethod.Post, "feedback", "attachments", "presign").acceptsActivityStamp())
        // The retired bodiless occurrence DELETE: gone upstream, and never stampable while it existed.
        assertFalse(request(OutboxMethod.Delete, "chores", "c-1", "occurrences", "2026-07-24", body = null).acceptsActivityStamp())
        // An occurrence subresource outside the {clear, reschedule} pair.
        assertFalse(request(OutboxMethod.Post, "habits", "h-1", "occurrences", "2026-07-24", "skip").acceptsActivityStamp())
        // An attachment subresource outside the delete leaf, and a caption PATCH missing its id.
        assertFalse(request(OutboxMethod.Post, "items", "t-1", "attachments", "a-1", "thumbnail").acceptsActivityStamp())
        assertFalse(request(OutboxMethod.Patch, "items", "t-1", "attachments").acceptsActivityStamp())
        // A PUT on the habit-shaped occurrences collection (the chore verb on the habit route).
        assertFalse(request(OutboxMethod.Put, "habits", "h-1", "occurrences").acceptsActivityStamp())
    }

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
    fun mergingIsANoOpOnARouteThatCannotCarryAStamp() {
        // Belt-and-braces with the whitelist: even if a caller stamps unconditionally, a non-whitelisted
        // route's bytes go out untouched rather than 422-ing and dead-lettering.
        val settings = SetTheme(ThemeFamily.Mono, ThemeMode.Dark).toRequest()
        assertSame(settings, settings.withActivityStamp(stamp))
    }

    @Test
    fun mergingIsANoOpWhenTheRequestHasNoBody() {
        // A null body sends no HTTP entity, so there is nothing to merge into — and inventing one would
        // turn a bodiless route into a bodied request. `POST items/{id}/convert` is exactly this case.
        val convert = request(OutboxMethod.Post, "items", "i-1", "convert", body = null)
        assertTrue(convert.acceptsActivityStamp())
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

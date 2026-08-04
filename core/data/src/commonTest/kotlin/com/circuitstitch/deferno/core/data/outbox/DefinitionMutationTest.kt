package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recurring-definition half of the intent → endpoint → minimal-body table (#378/#389), the sibling
 * of `MutationTest` for the intents that live in `DefinitionMutation.kt`.
 *
 * **The path assertions are the point, not decoration.** `KtorOutboxRequestSender.outcomeFor` maps a
 * `404` to `SendOutcome.Success` ("already gone — idempotent under LWW"), so a write sent to the wrong
 * route is not a failure the queue reports: it drains, the entry is deleted, and the user's edit
 * evaporates silently. Nothing downstream can catch that, so the literal segments are pinned here per
 * intent **per kind** — the endpoint is kind-selected, so a kind that drifted would drift alone.
 */
class DefinitionMutationTest {

    private val deadline = Instant.parse("2026-06-08T17:00:00Z")
    private val wanted = Instant.parse("2026-06-05T09:00:00Z")

    private fun fields(
        state: DefinitionState = DefinitionState.Active,
        targetDate: Instant? = null,
        priority: Priority = Priority.Normal,
        completeBy: Instant? = null,
    ) = DefinitionFields(state, targetDate, priority, completeBy)

    // --- SetDefinitionTargetDate: the soft date, per kind ---

    @Test
    fun setDefinitionTargetDateEmitsTheDatePatchPerKind() {
        val habit = SetDefinitionTargetDate("h1", ItemKind.Habit, wanted).toRequest()
        assertEquals(OutboxMethod.Patch, habit.method)
        assertEquals(listOf("habits", "h1"), habit.path)
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", habit.body)

        val chore = SetDefinitionTargetDate("c1", ItemKind.Chore, wanted).toRequest()
        assertEquals(listOf("chores", "c1"), chore.path)
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", chore.body)

        val event = SetDefinitionTargetDate("e1", ItemKind.Event, wanted).toRequest()
        assertEquals(listOf("events", "e1"), event.path)
        assertEquals("""{"target_date":"2026-06-05T09:00:00Z"}""", event.body)

        // Shared with SetDefinitionState via the patchRecurring builder — asserted per kind because the
        // endpoint is kind-selected, so a kind that lost the declaration would lose it silently.
        for (request in listOf(habit, chore, event)) {
            assertTrue(request.acceptsActivityStamp, "${request.path} must declare the activity stamp")
        }
    }

    @Test
    fun setDefinitionTargetDateWithNullEmitsExplicitNullToClearIt() {
        // null = "clear it" (ADR-0011), distinct from omit — the server reads target_date as a Patch<T>,
        // where an omitted key means "leave unchanged" and would silently no-op the clear.
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertEquals("""{"target_date":null}""", SetDefinitionTargetDate("x", kind, null).toRequest().body)
        }
    }

    @Test
    fun setDefinitionTargetDateAppliesTheServerClampAndIsIdempotent() {
        // The commonest recurring shape: a lapsed definition whose complete_by cursor sits in the past.
        // The backend's clamp_target_date (#629) pulls a later target down to the deadline at write time,
        // so an unclamped optimistic apply would show a date the server never stores.
        val lapsed = fields(completeBy = deadline)
        val late = SetDefinitionTargetDate("x", ItemKind.Habit, Instant.parse("2026-06-09T09:00:00Z"))
        assertEquals(deadline, late.apply(lapsed).targetDate)
        assertEquals(late.apply(lapsed), late.apply(late.apply(lapsed)), "apply must be idempotent")

        // Inside the deadline it is stored verbatim…
        assertEquals(wanted, SetDefinitionTargetDate("x", ItemKind.Habit, wanted).apply(lapsed).targetDate)
        // …and the bound is INCLUSIVE: exactly-at-the-deadline is not "past" it.
        assertEquals(deadline, SetDefinitionTargetDate("x", ItemKind.Habit, deadline).apply(lapsed).targetDate)
        // With no deadline there is nothing to clamp against.
        val far = Instant.parse("2030-01-01T00:00:00Z")
        assertEquals(far, SetDefinitionTargetDate("x", ItemKind.Habit, far).apply(fields()).targetDate)
        // And a clear is never clamped into a value.
        assertNull(SetDefinitionTargetDate("x", ItemKind.Habit, null).apply(lapsed).targetDate)
    }

    @Test
    fun setDefinitionTargetDateSendsTheRawValueEvenWhenTheApplyClamps() {
        // Only the optimistic apply clamps. The server holds the authoritative complete_by and clamps
        // against that; sending our (possibly stale) clamped value could overwrite a date it would keep.
        val past = Instant.parse("2026-06-09T09:00:00Z")
        val intent = SetDefinitionTargetDate("h1", ItemKind.Habit, past)
        assertEquals(deadline, intent.apply(fields(completeBy = deadline)).targetDate)
        assertEquals("""{"target_date":"2026-06-09T09:00:00Z"}""", intent.toRequest().body)
    }

    @Test
    fun setDefinitionTargetDateBeforeImageCarriesTheOldDateOrAnExplicitNull() {
        val intent = SetDefinitionTargetDate("h1", ItemKind.Habit, wanted)
        assertEquals("""{"target_date":"2026-06-08T17:00:00Z"}""", intent.beforeValues(fields(targetDate = deadline)).toString())
        // The same key the body uses, so a reader can zip old→new — an unset old date is an explicit null.
        assertEquals("""{"target_date":null}""", intent.beforeValues(fields()).toString())
    }

    @Test
    fun setDefinitionTargetDateTouchesNothingElseOnTheRow() {
        val before = fields(state = DefinitionState.InReview, priority = Priority.Fire, completeBy = deadline)
        val after = SetDefinitionTargetDate("x", ItemKind.Habit, wanted).apply(before)
        assertEquals(before.copy(targetDate = wanted), after)
    }

    // --- SetDefinitionPriority: the urgency bucket, per kind ---

    @Test
    fun setDefinitionPriorityEmitsThePriorityPatchPerKind() {
        val habit = SetDefinitionPriority("h1", ItemKind.Habit, Priority.Fire).toRequest()
        assertEquals(OutboxMethod.Patch, habit.method)
        assertEquals(listOf("habits", "h1"), habit.path)
        assertEquals("""{"priority":"fire"}""", habit.body)

        val chore = SetDefinitionPriority("c1", ItemKind.Chore, Priority.Backlog).toRequest()
        assertEquals(listOf("chores", "c1"), chore.path)
        assertEquals("""{"priority":"backlog"}""", chore.body)

        val event = SetDefinitionPriority("e1", ItemKind.Event, Priority.Normal).toRequest()
        assertEquals(listOf("events", "e1"), event.path)
        assertEquals("""{"priority":"normal"}""", event.body)

        for (request in listOf(habit, chore, event)) {
            assertTrue(request.acceptsActivityStamp, "${request.path} must declare the activity stamp")
        }
    }

    @Test
    fun setDefinitionPriorityNeverEmitsNullForAnyValue() {
        // The one nullable-looking field on the PATCH surface that must never emit JsonNull: the server
        // types it Option<Priority> with a plain serde(default), so a null folds to None — a silent 200
        // no-op that leaves the optimistic bucket wrong until the next reconcile quietly reverts it.
        // "Clearing" is spelled Normal, which is a real token, not an absence.
        for (priority in Priority.entries) {
            for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
                val body = SetDefinitionPriority("x", kind, priority).toRequest().body
                assertFalse(body!!.contains("null"), "priority must never serialise a null (was $body)")
            }
        }
    }

    @Test
    fun setDefinitionPriorityAppliesIsIdempotentAndTouchesNothingElse() {
        val before = fields(state = DefinitionState.InReview, targetDate = wanted, priority = Priority.Normal)
        val intent = SetDefinitionPriority("x", ItemKind.Chore, Priority.Backlog)
        assertEquals(before.copy(priority = Priority.Backlog), intent.apply(before))
        assertEquals(intent.apply(before), intent.apply(intent.apply(before)), "apply must be idempotent")
    }

    @Test
    fun setDefinitionPriorityBeforeImageCarriesTheOldBucketAsAToken() {
        // Always a real value — priority is never absent on a row — so this arm has no null branch.
        val intent = SetDefinitionPriority("x", ItemKind.Chore, Priority.Backlog)
        assertEquals("""{"priority":"fire"}""", intent.beforeValues(fields(priority = Priority.Fire)).toString())
    }

    // --- Targets + the Task guard, shared by both kind-scoped intents ---

    @Test
    fun definitionIntentsTargetTheRawItemId() {
        assertEquals("item:h1", SetDefinitionTargetDate("h1", ItemKind.Habit, wanted).target)
        assertEquals("item:c1", SetDefinitionPriority("c1", ItemKind.Chore, Priority.Fire).target)
        assertEquals("item:x", DeleteItem("x").target)
    }

    @Test
    fun everyKindScopedBuilderRejectsTask() {
        // A Task has no recurring endpoint. Failing loudly beats rendering `tasks`-shaped nonsense and
        // enqueueing it: the 404 that would come back is classified Success, so the write would vanish.
        assertFailsWith<IllegalStateException> { SetDefinitionTargetDate("t1", ItemKind.Task, wanted).toRequest() }
        assertFailsWith<IllegalStateException> { SetDefinitionPriority("t1", ItemKind.Task, Priority.Fire).toRequest() }
    }

    // --- DeleteItem: kind-neutral, chain-wide, bodiless ---

    @Test
    fun deleteItemIsABodilessKindNeutralDeleteAndDeclaresNoActivityStamp() {
        val request = DeleteItem("x").toRequest()
        assertEquals(OutboxMethod.Delete, request.method)
        // `items`, NOT `{habits|chores|events}`: the per-kind route archives ONE Segment of a recurrence
        // chain and leaves its siblings alive, so the survivor reappears as an item the user just deleted.
        assertEquals(listOf("items", "x"), request.path)
        assertEquals(null, request.body)
        // Bodiless upstream — declaring a stamp would push a key onto a route that accepts no entity.
        assertFalse(request.acceptsActivityStamp)
    }

    @Test
    fun deleteItemNeedsNoKindAndSoAcceptsATaskId() {
        // The kind-neutral route resolves the kind server-side, which is exactly why this intent takes no
        // ItemKind and why it does not go through the recurring path guard above.
        assertEquals(listOf("items", "t1"), DeleteItem("t1").toRequest().path)
    }
}

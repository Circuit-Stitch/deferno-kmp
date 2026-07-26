package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.RecurrenceDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline create intent (#185) — the create-shaped sibling of [MutationTest]: the kind-scoped
 * collection POST, the client-supplied `id` that makes a replay idempotent (rather than duplicating the
 * item), and the `activity` stamp declaration (#364).
 *
 * All four kinds share one private builder, so a regression on any one of them is a regression on all
 * four — hence the loop rather than four near-identical assertions.
 */
class CreateMutationTest {

    private val daily = RecurrenceDto(type = "daily")

    @Test
    fun everyKindPostsToItsCollectionUnderTheClientSuppliedId() {
        val cases = listOf(
            "tasks" to CreateTaskItem("i-1", CreateTaskPayload(title = "T")),
            "habits" to CreateHabitItem("i-2", CreateHabitPayload(title = "H", recurrence = daily)),
            "chores" to CreateChoreItem("i-3", CreateChorePayload(title = "C", recurrence = daily)),
            "events" to CreateEventItem("i-4", CreateEventPayload(title = "E", completeBy = "2026-07-24")),
        )
        for ((collection, mutation) in cases) {
            val request = mutation.toRequest()
            assertEquals(OutboxMethod.Post, request.method)
            assertEquals(listOf(collection), request.path)
            // The id leads the body: it is the idempotency key the backend dedupes a replay on, so a
            // create that lost it would insert a second item every time the outbox retried.
            assertTrue(
                request.body!!.startsWith("""{"id":"${mutation.itemId}""""),
                "POST /$collection must lead with the client-supplied id, got ${request.body}",
            )
            // The create routes take the `activity` sibling — it rides beside that same id, so an offline
            // create shows in the feed at the moment the user made it rather than at flush time.
            assertTrue(request.acceptsActivityStamp, "POST /$collection must declare the activity stamp")
        }
    }

    @Test
    fun targetNamesTheKindAndClientIdTheProcessorRoutesOn() {
        // The `create:` prefix is what routes a replay through the response-bearing sendCreate path (it
        // needs the server's returned id to confirm the pending row), distinct from every other intent.
        assertEquals(
            "create:Habit:i-2",
            CreateHabitItem("i-2", CreateHabitPayload(title = "H", recurrence = daily)).target,
        )
        assertEquals(ItemKind.Task, CreateTaskItem("i-1", CreateTaskPayload(title = "T")).itemKind)
    }
}

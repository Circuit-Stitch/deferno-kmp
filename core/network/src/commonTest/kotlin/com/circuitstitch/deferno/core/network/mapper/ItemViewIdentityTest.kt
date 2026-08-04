package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ItemView
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kind-neutral identity accessors (#385): an [ItemView]'s id and kind, without building the
 * concrete domain entity.
 *
 * The four `as*OrNull` mappers beside them partition a heterogeneous array into four typed lists,
 * which is exactly what the `/items` cold sync wants and exactly what an **ordered** read must not do —
 * the daily plan is a curation whose whole payload is the order, so partitioning it by kind destroys
 * the only thing it carries. These project one element to one ref, order intact, which is what makes
 * the plan resolvable across all four caches instead of only the Task one.
 */
class ItemViewIdentityTest {

    /** One row of each kind, in an order no per-kind partition would preserve. */
    private val mixedPlan = """
        [
          {"type":"habit","id":"h1","org_slug":"u-x","title":"Take a Walk","date_created":"2026-05-20T16:11:42Z"},
          {"type":"task","id":"t1","org_slug":"u-x","title":"Call the plumber","date_created":"2026-05-20T16:11:42Z"},
          {"type":"event","id":"e1","org_slug":"u-x","title":"Standup","date_created":"2026-05-20T16:11:42Z"},
          {"type":"chore","id":"c1","org_slug":"u-x","title":"Take shot","date_created":"2026-05-20T16:11:42Z"}
        ]
    """.trimIndent()

    private fun decode(): List<ItemView> =
        DefernoJson.decodeFromString(ListSerializer(ItemView.serializer()), mixedPlan)

    @Test
    fun itemIdUnwrapsEveryVariant() {
        assertEquals(listOf("h1", "t1", "e1", "c1"), decode().map { it.itemId })
    }

    @Test
    fun itemKindNamesEveryVariant() {
        assertEquals(
            listOf(ItemKind.Habit, ItemKind.Task, ItemKind.Event, ItemKind.Chore),
            decode().map { it.itemKind },
        )
    }

    /**
     * The property the plan actually depends on: projecting in place preserves the server's order. A
     * per-kind partition would reorder these four into task/habit/chore/event buckets and silently
     * discard the curation the day *is*.
     */
    @Test
    fun projectingInPlaceKeepsTheServersOrder() {
        assertEquals(
            listOf("h1" to ItemKind.Habit, "t1" to ItemKind.Task, "e1" to ItemKind.Event, "c1" to ItemKind.Chore),
            decode().map { it.itemId to it.itemKind },
        )
    }
}

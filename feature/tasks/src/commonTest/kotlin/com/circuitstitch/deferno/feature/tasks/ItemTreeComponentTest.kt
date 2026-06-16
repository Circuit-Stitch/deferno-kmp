package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.item.InMemoryItemFoldStore
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Item-tree pane component (ADR-0034, #227): it flattens the cross-kind Item forest into the
 * observable [ItemTreeState], persists fold toggles to the device-local store (re-flattening live), opens
 * detail only for Task rows, and delegates refresh. Run against the fakes on the ADR-0006 JVM-fast path.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drive the WhileSubscribed state flow
class ItemTreeComponentTest {

    private fun TestScope.component(
        items: FakeItemRepository,
        foldStore: InMemoryItemFoldStore = InMemoryItemFoldStore(),
        output: (ItemTreeComponent.Output) -> Unit = {},
    ) = DefaultItemTreeComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        itemRepository = items,
        foldStore = foldStore,
        output = output,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    private fun rootAndChild() = FakeItemRepository(
        listOf(
            Item(id = "root", kind = ItemKind.Task, title = "root", sequence = 0),
            Item(id = "child", kind = ItemKind.Task, title = "child", parentId = "root", sequence = 1),
        ),
    )

    @Test
    fun stateFlattensTheCrossKindForest() = runTest {
        val c = component(rootAndChild())
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("root", "child"), c.state.value.rows.map { it.item.id })
        assertTrue(c.state.value.rows.first().isExpanded)
    }

    @Test
    fun togglingAParentPersistsTheFoldAndReFlattensLive() = runTest {
        val foldStore = InMemoryItemFoldStore()
        val c = component(rootAndChild(), foldStore)
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        c.onToggleExpand("root", currentlyExpanded = true) // collapse the root
        advanceUntilIdle()

        assertEquals(listOf("root"), c.state.value.rows.map { it.item.id }, "child hidden under the collapsed root")
        assertFalse(c.state.value.rows.single().isExpanded)
        assertEquals(false, foldStore.overrides.value["root"], "the collapse is persisted device-locally")
    }

    @Test
    fun seedsItsFoldFromThePersistedOverrides() = runTest {
        // The root would expand by default (depth 0), but a persisted collapse override wins.
        val c = component(rootAndChild(), InMemoryItemFoldStore(initial = mapOf("root" to false)))
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("root"), c.state.value.rows.map { it.item.id })
        assertFalse(c.state.value.rows.single().isExpanded)
    }

    @Test
    fun openingATaskRowEmitsItsDetailIntent() = runTest {
        val outputs = mutableListOf<ItemTreeComponent.Output>()
        val c = component(rootAndChild(), output = outputs::add)

        c.onOpenDetail("root", ItemKind.Task)

        assertEquals(listOf<ItemTreeComponent.Output>(ItemTreeComponent.Output.ItemSelected(TaskId("root"))), outputs)
    }

    @Test
    fun openingANonTaskRowEmitsNothing() = runTest {
        val outputs = mutableListOf<ItemTreeComponent.Output>()
        val c = component(rootAndChild(), output = outputs::add)

        c.onOpenDetail("some-habit", ItemKind.Habit)

        assertTrue(outputs.isEmpty())
    }

    @Test
    fun refreshDelegatesToTheRepository() = runTest {
        val items = rootAndChild()
        val c = component(items)

        c.onRefresh()
        advanceUntilIdle()

        assertEquals(1, items.refreshCount)
    }
}

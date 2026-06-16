package com.circuitstitch.deferno.core.data.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The device-local fold overrides of [InMemoryItemFoldStore] (ADR-0034, #227) — the measured contract
 * the production `Settings`-backed store mirrors. Round-trips overrides and exposes the whole map so a
 * tree surface can seed its fold state.
 */
class ItemFoldStoreTest {

    @Test
    fun startsWithNoOverrides() {
        assertEquals(emptyMap(), InMemoryItemFoldStore().allOverrides())
    }

    @Test
    fun persistsAndExposesExplicitOverrides() {
        val store = InMemoryItemFoldStore()
        store.setOverride("a", expanded = true)
        store.setOverride("b", expanded = false)

        assertEquals(mapOf("a" to true, "b" to false), store.allOverrides())
    }

    @Test
    fun aLaterOverrideReplacesTheEarlierOneForTheSameItem() {
        val store = InMemoryItemFoldStore(initial = mapOf("a" to true))
        store.setOverride("a", expanded = false)

        assertEquals(false, store.allOverrides()["a"])
    }

    @Test
    fun hasNoEntryForAnItemThatWasNeverToggled() {
        assertNull(InMemoryItemFoldStore(initial = mapOf("a" to true)).allOverrides()["never-set"])
    }
}

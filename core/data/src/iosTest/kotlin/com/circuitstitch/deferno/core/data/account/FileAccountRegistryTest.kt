package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlinx.coroutines.test.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [FileAccountRegistry] file IO against a real (temporary) directory: the registry contract plus
 * the property the in-memory placeholder could never give — **a fresh instance reads what a previous
 * one wrote** (the cold-start session-restore regression, the "sign in again on every launch" defect).
 */
@OptIn(ExperimentalForeignApi::class)
class FileAccountRegistryTest {

    private val dir = "${NSTemporaryDirectory()}roster-test-${Random.nextLong()}"
    private val work = Account(AccountId("acct-a"), "Work")
    private val personal = Account(AccountId("acct-b"), "Personal", tokenId = "tok-1")

    @Test
    fun startsEmpty() = runTest {
        val registry = FileAccountRegistry(dir)
        assertTrue(registry.all().isEmpty())
        assertNull(registry.activeId())
    }

    @Test
    fun putsUpsertingInPlaceAndPreservingOrder() = runTest {
        val registry = FileAccountRegistry(dir)
        registry.put(work)
        registry.put(personal)
        registry.put(work.copy(label = "Work v2")) // upsert keeps position
        assertContentEquals(listOf(work.copy(label = "Work v2"), personal), registry.all())
    }

    @Test
    fun removesAndClearsActiveIndependently() = runTest {
        val registry = FileAccountRegistry(dir)
        registry.put(work)
        registry.put(personal)
        registry.setActive(work.id)
        assertEquals(work.id, registry.activeId())

        registry.remove(work.id)
        assertContentEquals(listOf(personal), registry.all())
        // Dumb storage: removal does not touch the active id (the AccountManager owns that invariant).
        assertEquals(work.id, registry.activeId())

        registry.setActive(null)
        assertNull(registry.activeId())
    }

    @Test
    fun freshInstanceReadsWhatAPreviousOneWrote() = runTest {
        val first = FileAccountRegistry(dir)
        first.put(work)
        first.put(personal)
        first.setActive(personal.id)

        // A new instance over the same directory = the next app launch (cold-start restore).
        val second = FileAccountRegistry(dir)
        assertContentEquals(listOf(work, personal), second.all())
        assertEquals(personal.id, second.activeId())
    }

    @Test
    fun corruptFileDegradesToEmptyAndRecovers() = runTest {
        val registry = FileAccountRegistry(dir)
        registry.put(work)

        ("{corrupt" as NSString).writeToFile(
            "$dir/account_roster.json",
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        assertTrue(registry.all().isEmpty(), "corrupt roster must degrade to empty (ADR-0009)")

        registry.put(personal) // and the registry must be writable again afterwards
        assertContentEquals(listOf(personal), registry.all())
    }
}

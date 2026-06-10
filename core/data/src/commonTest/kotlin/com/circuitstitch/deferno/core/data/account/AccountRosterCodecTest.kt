package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip + tolerance of [AccountRosterCodec] — the pure (de)serialization logic behind the
 * persistent [SharedPreferencesAccountRegistry] (the SharedPreferences I/O itself is device-only and
 * excluded from the headless gate). Covers order preservation, empties, and the ADR-0009 "corrupt
 * roster degrades to empty" posture.
 */
class AccountRosterCodecTest {
    private val a = Account(AccountId("acct-a"), "Work")
    private val b = Account(AccountId("acct-b"), "Personal, Inc.") // comma proves no delimiter fragility

    @Test
    fun roundTripsPreservingOrder() {
        val roster = listOf(a, b)
        assertContentEquals(roster, AccountRosterCodec.decode(AccountRosterCodec.encode(roster)))
    }

    @Test
    fun emptyRosterRoundTrips() {
        assertTrue(AccountRosterCodec.decode(AccountRosterCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun nullOrBlankDecodesToEmpty() {
        assertTrue(AccountRosterCodec.decode(null).isEmpty())
        assertTrue(AccountRosterCodec.decode("").isEmpty())
        assertTrue(AccountRosterCodec.decode("   ").isEmpty())
    }

    @Test
    fun malformedJsonDecodesToEmpty() {
        assertTrue(AccountRosterCodec.decode("{not valid").isEmpty())
        assertTrue(AccountRosterCodec.decode("\"a string\"").isEmpty()) // valid JSON, not an array
        assertTrue(AccountRosterCodec.decode("{\"id\":\"x\"}").isEmpty()) // object, not an array
    }

    @Test
    fun entriesWithBlankIdAreSkipped() {
        val json = "[{\"id\":\"\",\"label\":\"ghost\"},{\"id\":\"acct-a\",\"label\":\"Work\"}]"
        assertContentEquals(listOf(a), AccountRosterCodec.decode(json))
    }

    @Test
    fun labelDefaultsToEmptyWhenMissing() {
        assertContentEquals(
            listOf(Account(AccountId("x"), "")),
            AccountRosterCodec.decode("[{\"id\":\"x\"}]"),
        )
    }

    @Test
    fun roundTripsTheTokenIdWhenPresentAndOmitsItOtherwise() {
        // A browser-minted account carries its token id (ADR-0026); a pasted/dev one does not.
        val minted = Account(AccountId("acct-c"), "Browser", tokenId = "tok-xyz")
        val roster = listOf(a, minted)

        val encoded = AccountRosterCodec.encode(roster)
        assertTrue(encoded.contains("\"token_id\":\"tok-xyz\""), encoded)
        // The token-less account must not emit a token_id key at all.
        assertTrue(!encoded.substringBefore("acct-c").contains("token_id"), encoded)
        assertContentEquals(roster, AccountRosterCodec.decode(encoded))
    }

    @Test
    fun blankTokenIdDecodesToNull() {
        val decoded = AccountRosterCodec.decode("[{\"id\":\"x\",\"label\":\"L\",\"token_id\":\"\"}]")
        assertContentEquals(listOf(Account(AccountId("x"), "L", tokenId = null)), decoded)
    }

    // --- Document form (roster + active selection in one string, for single-file registries) ---

    @Test
    fun documentRoundTripsRosterAndActive() {
        val encoded = AccountRosterCodec.encodeDocument(listOf(a, b), a.id)
        val decoded = AccountRosterCodec.decodeDocument(encoded)
        assertContentEquals(listOf(a, b), decoded.accounts)
        assertEquals(a.id, decoded.activeId)
    }

    @Test
    fun documentOmitsActiveWhenNone() {
        val encoded = AccountRosterCodec.encodeDocument(listOf(a), activeId = null)
        assertTrue(!encoded.contains("active"), encoded)
        assertNull(AccountRosterCodec.decodeDocument(encoded).activeId)
    }

    @Test
    fun documentDegradesToEmptyOnNullBlankOrMalformedInput() {
        listOf(null, "", "   ", "{not valid", "\"a string\"", "[]").forEach { input ->
            val decoded = AccountRosterCodec.decodeDocument(input)
            assertTrue(decoded.accounts.isEmpty(), "accounts for $input")
            assertNull(decoded.activeId, "active for $input")
        }
    }

    @Test
    fun documentToleratesMissingOrInvalidFields() {
        // No roster key → no accounts; the active id still decodes (the manager coerces dangling ids).
        assertEquals(
            AccountRosterCodec.Document(emptyList(), AccountId("ghost")),
            AccountRosterCodec.decodeDocument("{\"active\":\"ghost\"}"),
        )
        // Blank active / non-primitive active → none.
        assertNull(AccountRosterCodec.decodeDocument("{\"active\":\"\",\"roster\":[]}").activeId)
        assertNull(AccountRosterCodec.decodeDocument("{\"active\":{},\"roster\":[]}").activeId)
        // A non-array roster degrades to no accounts.
        assertTrue(AccountRosterCodec.decodeDocument("{\"roster\":{}}").accounts.isEmpty())
    }

    @Test
    fun documentPreservesTokenIdAndSkipsBlankIdEntries() {
        val minted = Account(AccountId("acct-c"), "Browser", tokenId = "tok-xyz")
        val decoded = AccountRosterCodec.decodeDocument(
            AccountRosterCodec.encodeDocument(listOf(minted), minted.id),
        )
        assertContentEquals(listOf(minted), decoded.accounts)

        val withGhost = "{\"roster\":[{\"id\":\"\",\"label\":\"ghost\"},{\"id\":\"acct-a\",\"label\":\"Work\"}]}"
        assertContentEquals(listOf(a), AccountRosterCodec.decodeDocument(withGhost).accounts)
    }
}

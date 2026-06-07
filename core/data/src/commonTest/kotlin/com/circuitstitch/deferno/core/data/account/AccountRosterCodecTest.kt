package com.circuitstitch.deferno.core.data.account

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
}

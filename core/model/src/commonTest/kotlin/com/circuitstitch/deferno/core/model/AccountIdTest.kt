package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Contract for the [AccountId] identity value class (relocated from `core:secure` with issue #14):
 * it rejects blank values so a malformed id can never become a vault / DB / registry key. Runs on
 * the JVM-fast path (ADR-0006).
 */
class AccountIdTest {
    @Test
    fun rejectsBlankValues() {
        assertFailsWith<IllegalArgumentException> { AccountId("") }
        assertFailsWith<IllegalArgumentException> { AccountId("   ") }
    }

    @Test
    fun preservesItsValue() {
        assertEquals("account-a", AccountId("account-a").value)
    }
}

package com.circuitstitch.deferno

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The dev-account PAT parser (#68, ADR-0012): turns the `local.properties`-sourced BuildConfig fields
 * into the Accounts the host app seeds. Pure logic — exercised here on the JVM-fast path.
 */
class DevAccountsTest {

    @Test
    fun blankInputs_yieldNoAccounts() {
        assertEquals(emptyList<DevAccount>(), DevAccounts.from(devAccounts = "", stagingToken = ""))
    }

    @Test
    fun parsesASingleIdLabelTokenEntry() {
        assertEquals(
            listOf(DevAccount(Account(AccountId("work"), "Work"), "tok-1")),
            DevAccounts.from(devAccounts = "work:Work:tok-1", stagingToken = ""),
        )
    }

    @Test
    fun parsesMultipleSemicolonSeparatedEntriesInOrder() {
        assertEquals(
            listOf(
                DevAccount(Account(AccountId("work"), "Work"), "tok-1"),
                DevAccount(Account(AccountId("personal"), "Personal"), "tok-2"),
            ),
            DevAccounts.from(devAccounts = "work:Work:tok-1;personal:Personal:tok-2", stagingToken = ""),
        )
    }

    @Test
    fun stagingTokenAlone_seedsBackCompatDevAccount() {
        assertEquals(
            listOf(DevAccount(Account(AccountId("dev"), "Dev (staging)"), "tok-staging")),
            DevAccounts.from(devAccounts = "", stagingToken = "tok-staging"),
        )
    }

    @Test
    fun stagingTokenIsAppendedAfterExplicitEntries() {
        val result = DevAccounts.from(devAccounts = "work:Work:tok-1", stagingToken = "tok-staging")
        assertEquals(
            listOf(
                DevAccount(Account(AccountId("work"), "Work"), "tok-1"),
                DevAccount(Account(AccountId("dev"), "Dev (staging)"), "tok-staging"),
            ),
            result,
        )
    }

    @Test
    fun anExplicitDevEntrySuppressesTheBackCompatStagingAccount() {
        assertEquals(
            listOf(DevAccount(Account(AccountId("dev"), "My Dev"), "tok-explicit")),
            DevAccounts.from(devAccounts = "dev:My Dev:tok-explicit", stagingToken = "tok-staging"),
        )
    }

    @Test
    fun malformedEntriesAreSkipped() {
        // missing token, missing fields, blank id — all dropped; the valid one survives.
        assertEquals(
            listOf(DevAccount(Account(AccountId("ok"), "Ok"), "tok")),
            DevAccounts.from(devAccounts = "nope;:Label:tok; ;ok:Ok:tok;onlyid", stagingToken = ""),
        )
    }

    @Test
    fun tokenKeepsColonsWithItsBody() {
        // A JWT-shaped token has no ':' but a scheme-y one might; limit-3 split keeps the remainder.
        assertEquals(
            listOf(DevAccount(Account(AccountId("a"), "A"), "scheme:opaque:value")),
            DevAccounts.from(devAccounts = "a:A:scheme:opaque:value", stagingToken = ""),
        )
    }

    @Test
    fun blankLabelFallsBackToId() {
        assertEquals(
            listOf(DevAccount(Account(AccountId("work"), "work"), "tok")),
            DevAccounts.from(devAccounts = "work::tok", stagingToken = ""),
        )
    }

    @Test
    fun duplicateIds_lastWins_orderPreserved() {
        assertEquals(
            listOf(
                DevAccount(Account(AccountId("work"), "Work 2"), "tok-2"),
                DevAccount(Account(AccountId("personal"), "Personal"), "tok-3"),
            ),
            DevAccounts.from(
                devAccounts = "work:Work 1:tok-1;personal:Personal:tok-3;work:Work 2:tok-2",
                stagingToken = "",
            ),
        )
    }
}

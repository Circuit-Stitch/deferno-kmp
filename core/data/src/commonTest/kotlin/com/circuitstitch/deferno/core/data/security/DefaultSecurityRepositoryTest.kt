package com.circuitstitch.deferno.core.data.security

import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.RecordingReauthRequester
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DefaultSecurityRepository]: it returns the remote source's [SecurityResult] unchanged and, on
 * [SecurityResult.Unauthorized], routes **only the Active Account** to re-auth (ADR-0002 hard
 * isolation) — the same contract [com.circuitstitch.deferno.core.data.auth.DefaultAuthRepository]
 * gives `/auth/me`, pinned the same way with a recording re-auth requester. Two edges matter here:
 * step-up's [SecurityResult.Rejected] (a wrong *password*, not an expired PAT) must never trip
 * re-auth, and the [SecurityRepository.Inert] default must report [SecurityResult.Unavailable]
 * from every call so an unwired host renders the unavailable state instead of crashing.
 */
class DefaultSecurityRepositoryTest {

    private val active = Account(AccountId("acc-1"), "Work")

    @Test
    fun unauthorizedFromEveryCallRoutesOnlyTheActiveAccountToReauth() = runTest {
        val reauth = RecordingReauthRequester()
        val repo = DefaultSecurityRepository(
            FakeSecurityRemoteSource(every = SecurityResult.Unauthorized),
            accountContext(active),
            reauth,
        )

        val calls = surface(repo)
        calls.forEach { (name, call) ->
            // Returned unchanged — the component renders the 401; the raise is a side effect.
            assertEquals(SecurityResult.Unauthorized, call(), name)
        }
        // Exactly the Active Account, once per 401 — never a global sign-out (ADR-0002).
        assertEquals(List(calls.size) { AccountId("acc-1") }, reauth.requested)
    }

    @Test
    fun rejectedStepUpIsAWrongPasswordNotAReauthSignal() = runTest {
        // Step-up's own 401 comes back from the source as Rejected (the PAT authenticated fine) —
        // raising re-auth here would sign the user out over a password typo.
        val reauth = RecordingReauthRequester()
        val repo = DefaultSecurityRepository(
            FakeSecurityRemoteSource(stepUpResult = SecurityResult.Rejected),
            accountContext(active),
            reauth,
        )

        assertEquals(SecurityResult.Rejected, repo.stepUp("wrong-password"))
        assertTrue(reauth.requested.isEmpty())
    }

    @Test
    fun successPassesThroughUntouchedAndRaisesNoReauth() = runTest {
        val status = MfaStatus(totpEnabled = true, emailBackup = false)
        val reauth = RecordingReauthRequester()
        val repo = DefaultSecurityRepository(
            FakeSecurityRemoteSource(statusResult = SecurityResult.Success(status)),
            accountContext(active),
            reauth,
        )

        assertEquals(SecurityResult.Success(status), repo.status())
        assertTrue(reauth.requested.isEmpty())
    }

    @Test
    fun a401WithNoActiveAccountRaisesNoReauth() = runTest {
        // Defensive (mirrors DefaultAuthRepositoryTest): a request only carries a PAT when an
        // Account is active — but a missing Active Account must raise nothing rather than crash.
        val reauth = RecordingReauthRequester()
        val repo = DefaultSecurityRepository(
            FakeSecurityRemoteSource(every = SecurityResult.Unauthorized),
            accountContext(active = null),
            reauth,
        )

        assertEquals(SecurityResult.Unauthorized, repo.status())
        assertTrue(reauth.requested.isEmpty())
    }

    @Test
    fun inertReportsUnavailableFromEveryCall() = runTest {
        // The unwired-host/test default: the whole surface degrades to Unavailable, never throws.
        surface(SecurityRepository.Inert).forEach { (name, call) ->
            assertEquals(SecurityResult.Unavailable, call(), name)
        }
    }

    /** The full repository surface, named, so the sweep tests pin every delegate — not just one. */
    private fun surface(repo: SecurityRepository): List<Pair<String, suspend () -> SecurityResult<*>>> = listOf(
        "status" to suspend { repo.status() },
        "stepUp" to suspend { repo.stepUp("hunter2") },
        "enrollStart" to suspend { repo.enrollStart() },
        "enrollVerify" to suspend { repo.enrollVerify("123456") },
        "addEmailBackup" to suspend { repo.addEmailBackup() },
        "removeEmailBackup" to suspend { repo.removeEmailBackup() },
        "disableMfa" to suspend { repo.disableMfa() },
        "connectedDevices" to suspend { repo.connectedDevices() },
        "revokeDevice" to suspend { repo.revokeDevice("tok-1") },
    )

    private fun accountContext(active: Account?): AccountContext = object : AccountContext {
        override val activeAccount: StateFlow<Account?> = MutableStateFlow(active)
    }
}

/**
 * Test [SecurityRemoteSource] returning programmed results, no HTTP plumbing — [every] sets the
 * whole surface at once (the 401-everywhere sweep); the per-call parameters override one call
 * (a typed [SecurityResult.Success], or step-up's [SecurityResult.Rejected]).
 */
private class FakeSecurityRemoteSource(
    every: SecurityResult<Nothing> = SecurityResult.Unavailable,
    private val statusResult: SecurityResult<MfaStatus> = every,
    private val stepUpResult: SecurityResult<Unit> = every,
    private val enrollStartResult: SecurityResult<TotpEnrollment> = every,
    private val enrollVerifyResult: SecurityResult<List<String>> = every,
    private val addBackupResult: SecurityResult<Unit> = every,
    private val removeBackupResult: SecurityResult<Unit> = every,
    private val disableResult: SecurityResult<Unit> = every,
    private val devicesResult: SecurityResult<List<ConnectedDevice>> = every,
    private val revokeResult: SecurityResult<Unit> = every,
) : SecurityRemoteSource {
    override suspend fun fetchStatus(): SecurityResult<MfaStatus> = statusResult
    override suspend fun stepUp(password: String): SecurityResult<Unit> = stepUpResult
    override suspend fun enrollStart(): SecurityResult<TotpEnrollment> = enrollStartResult
    override suspend fun enrollVerify(code: String): SecurityResult<List<String>> = enrollVerifyResult
    override suspend fun addEmailBackup(): SecurityResult<Unit> = addBackupResult
    override suspend fun removeEmailBackup(): SecurityResult<Unit> = removeBackupResult
    override suspend fun disableMfa(): SecurityResult<Unit> = disableResult
    override suspend fun fetchConnectedDevices(): SecurityResult<List<ConnectedDevice>> = devicesResult
    override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> = revokeResult
}

package com.circuitstitch.deferno.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.security.SecurityRepository
import com.circuitstitch.deferno.core.data.security.SecurityResult
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [DefaultSettingsComponent]'s Security & 2FA step machine (#72 follow-through) — the native port of
 * the web SecurityPane's behavior, pinned end to end:
 *
 * - the drill-in fetches **fresh** server truth (status + devices) and a re-entry never resumes a
 *   stale flow step;
 * - a gated mutation the server refuses with the step-up 403 opens [SecuritySettings.Flow.StepUp]
 *   carrying the *typed* pending action, and a successful password re-verification **resumes that
 *   exact action** — including re-verifying the same code against the same secret (no fresh QR);
 * - the enroll happy path walks EnterCode → RecoveryCodes → summary-enrolled, and the one-shot
 *   recovery codes are exited only by explicit acknowledgment;
 * - a wrong TOTP code returns to code entry against the SAME enrollment with the error flagged;
 * - disable/backup flips update the summary in place; revoke prunes the device list in place; and
 * - the Active Account's own device row can never be revoked from here (sign-out owns that path).
 *
 * Driven on [Dispatchers.Unconfined] so every transition is observable synchronously.
 */
class SecuritySettingsComponentTest {

    private val enrollment = TotpEnrollment(secret = "SECRETKEY", uri = "otpauth://totp/Deferno:kyle?secret=SECRETKEY")
    private val recoveryCodes = List(10) { "aaaaa-bbbb$it" }

    private fun device(id: String, name: String = "Pixel $id") = ConnectedDevice(
        id = id,
        name = name,
        createdAt = Instant.parse("2026-06-15T10:30:00Z"),
        lastUsedAt = null,
    )

    private fun component(
        security: SecurityRepository,
        activeTokenId: String? = null,
    ): DefaultSettingsComponent = DefaultSettingsComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        settingsRepository = FakeSettingsRepository(),
        settingsEditor = FakeSettingsEditor(FakeSettingsRepository()),
        securityRepository = security,
        activeTokenId = activeTokenId,
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun DefaultSettingsComponent.securityNow(): SecuritySettings = security.value

    @Test
    fun drillInFetchesFreshStatusAndDevices() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = true, emailBackup = true)),
            devicesResult = SecurityResult.Success(listOf(device("tok-1"), device("tok-2"))),
        )
        val component = component(repo, activeTokenId = "tok-1")

        component.openCategory(SettingsCategory.Security2FA)

        val overview = assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview)
        assertTrue(overview.totpEnabled)
        assertTrue(overview.emailBackup)
        val devices = assertIs<SecuritySettings.Devices.Ready>(component.securityNow().devices)
        assertEquals(listOf("tok-1", "tok-2"), devices.devices.map { it.id })
        // The Active Account's own token id rides along so the View can mark "This device".
        assertEquals("tok-1", devices.activeTokenId)
    }

    @Test
    fun unavailableStatusRendersUnavailableAndRetryRefetches() = runTest {
        val repo = FakeSecurityRepository(statusResult = SecurityResult.Unavailable)
        val component = component(repo)

        component.openCategory(SettingsCategory.Security2FA)
        assertIs<SecuritySettings.Overview.Unavailable>(component.securityNow().overview)

        // Connectivity returns; the retry intent re-reads server truth.
        repo.statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false))
        component.onSecurityRetry()
        val overview = assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview)
        assertFalse(overview.totpEnabled)
    }

    @Test
    fun enrollHappyPath_walksEnterCodeThenRecoveryCodesThenEnrolledSummary() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.Success(enrollment),
            enrollVerifyResult = SecurityResult.Success(recoveryCodes),
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        component.onEnrollTotp()
        val enterCode = assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)
        assertEquals(enrollment, enterCode.enrollment)

        component.onEnrollCodeSubmit("123456")
        assertEquals("123456", repo.lastVerifiedCode)
        val recovery = assertIs<SecuritySettings.Flow.RecoveryCodes>(component.securityNow().flow)
        assertEquals(recoveryCodes, recovery.codes)
        // The summary flips to enrolled the moment the verify succeeds (server truth already changed).
        val overview = assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview)
        assertTrue(overview.totpEnabled)

        // The one exit from the one-shot codes is the explicit acknowledgment.
        component.onRecoveryCodesAcknowledged()
        assertNull(component.securityNow().flow)
    }

    @Test
    fun stepUpInterrupt_resumesTheExactPendingAction() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.StepUpRequired,
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        // The server refuses the mutation for freshness → the step-up sheet opens over the summary.
        component.onEnrollTotp()
        val stepUp = assertIs<SecuritySettings.Flow.StepUp>(component.securityNow().flow)
        assertEquals(PendingSecurityAction.EnrollTotp, stepUp.pending)

        // Wrong password: stay on the sheet with the error flagged, nothing dispatched.
        repo.stepUpResult = SecurityResult.Rejected
        component.onStepUpSubmit("wrong")
        assertTrue(assertIs<SecuritySettings.Flow.StepUp>(component.securityNow().flow).wrongPassword)

        // Right password: the stamp lands and the SAME pending action resumes → code entry opens.
        repo.stepUpResult = SecurityResult.Success(Unit)
        repo.enrollStartResult = SecurityResult.Success(enrollment)
        component.onStepUpSubmit("hunter2")
        assertEquals("hunter2", repo.lastStepUpPassword)
        assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)
    }

    @Test
    fun stepUpDuringVerify_resumesTheSameCodeAgainstTheSameSecret() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.Success(enrollment),
            // The step-up stamp expired mid-enrollment: the verify itself is refused.
            enrollVerifyResult = SecurityResult.StepUpRequired,
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)
        component.onEnrollTotp()
        component.onEnrollCodeSubmit("654321")

        val stepUp = assertIs<SecuritySettings.Flow.StepUp>(component.securityNow().flow)
        val pending = assertIs<PendingSecurityAction.VerifyCode>(stepUp.pending)
        assertEquals("654321", pending.code)
        assertEquals(enrollment, pending.enrollment)

        // After the password lands, the resume re-verifies the SAME code — no fresh enrollment.
        repo.stepUpResult = SecurityResult.Success(Unit)
        repo.enrollVerifyResult = SecurityResult.Success(recoveryCodes)
        component.onStepUpSubmit("hunter2")
        assertEquals("654321", repo.lastVerifiedCode)
        assertEquals(1, repo.enrollStartCalls) // never restarted
        assertIs<SecuritySettings.Flow.RecoveryCodes>(component.securityNow().flow)
    }

    @Test
    fun wrongCode_returnsToCodeEntryAgainstTheSameEnrollment() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.Success(enrollment),
            enrollVerifyResult = SecurityResult.Rejected,
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)
        component.onEnrollTotp()

        component.onEnrollCodeSubmit("000000")

        val enterCode = assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)
        assertTrue(enterCode.wrongCode)
        assertEquals(enrollment, enterCode.enrollment) // same secret — no new QR/key
    }

    @Test
    fun disable_flipsTheSummaryOffInPlace() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = true, emailBackup = true)),
            disableResult = SecurityResult.Success(Unit),
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        component.onDisableMfa()

        val overview = assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview)
        assertFalse(overview.totpEnabled)
        assertFalse(overview.emailBackup)
        assertNull(component.securityNow().flow)
    }

    @Test
    fun emailBackup_addAndRemoveFlipTheSummaryInPlace() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = true, emailBackup = false)),
            backupAddResult = SecurityResult.Success(Unit),
            backupRemoveResult = SecurityResult.Success(Unit),
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        component.onAddEmailBackup()
        assertTrue(assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview).emailBackup)

        component.onRemoveEmailBackup()
        assertFalse(assertIs<SecuritySettings.Overview.Ready>(component.securityNow().overview).emailBackup)
    }

    @Test
    fun revoke_prunesTheDeviceInPlace_andTheActiveDeviceIsNeverRevocable() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            devicesResult = SecurityResult.Success(listOf(device("tok-this"), device("tok-other"))),
            revokeResult = SecurityResult.Success(Unit),
        )
        val component = component(repo, activeTokenId = "tok-this")
        component.openCategory(SettingsCategory.Security2FA)

        // The Active Account's own token: a guarded no-op (sign-out owns that path).
        component.onRevokeDevice("tok-this")
        assertEquals(0, repo.revokedTokenIds.size)

        component.onRevokeDevice("tok-other")
        assertEquals(listOf("tok-other"), repo.revokedTokenIds)
        val devices = assertIs<SecuritySettings.Devices.Ready>(component.securityNow().devices)
        assertEquals(listOf("tok-this"), devices.devices.map { it.id })
    }

    @Test
    fun transientFailure_flagsLastActionFailed_andTheNextIntentClearsIt() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.Unavailable,
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        component.onEnrollTotp()
        assertTrue(component.securityNow().lastActionFailed)
        assertNull(component.securityNow().flow) // no step opened — the summary stays actionable

        repo.enrollStartResult = SecurityResult.Success(enrollment)
        component.onEnrollTotp()
        assertFalse(component.securityNow().lastActionFailed)
        assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)
    }

    @Test
    fun reEnteringTheCategoryDropsAStaleFlowStep() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.Success(enrollment),
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)
        component.onEnrollTotp()
        assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)

        // Back out mid-enrollment and drill in again: fresh read, no resumed step.
        component.onBack()
        component.openCategory(SettingsCategory.Security2FA)
        assertNull(component.securityNow().flow)
    }

    @Test
    fun dismissals_closeTheirStepWithoutMutating() = runTest {
        val repo = FakeSecurityRepository(
            statusResult = SecurityResult.Success(MfaStatus(totpEnabled = false, emailBackup = false)),
            enrollStartResult = SecurityResult.StepUpRequired,
        )
        val component = component(repo)
        component.openCategory(SettingsCategory.Security2FA)

        component.onEnrollTotp()
        assertIs<SecuritySettings.Flow.StepUp>(component.securityNow().flow)
        component.onStepUpDismiss()
        assertNull(component.securityNow().flow)

        repo.enrollStartResult = SecurityResult.Success(enrollment)
        component.onEnrollTotp()
        assertIs<SecuritySettings.Flow.EnterCode>(component.securityNow().flow)
        component.onEnrollDismiss()
        assertNull(component.securityNow().flow)
        assertEquals(0, repo.revokedTokenIds.size)
        assertNull(repo.lastVerifiedCode)
    }
}

/** A [SecurityRepository] with per-call scriptable results + call recording. `var`s let a test flip outcomes mid-flow. */
private class FakeSecurityRepository(
    var statusResult: SecurityResult<MfaStatus> = SecurityResult.Unavailable,
    var devicesResult: SecurityResult<List<ConnectedDevice>> = SecurityResult.Success(emptyList()),
    var stepUpResult: SecurityResult<Unit> = SecurityResult.Unavailable,
    var enrollStartResult: SecurityResult<TotpEnrollment> = SecurityResult.Unavailable,
    var enrollVerifyResult: SecurityResult<List<String>> = SecurityResult.Unavailable,
    var backupAddResult: SecurityResult<Unit> = SecurityResult.Unavailable,
    var backupRemoveResult: SecurityResult<Unit> = SecurityResult.Unavailable,
    var disableResult: SecurityResult<Unit> = SecurityResult.Unavailable,
    var revokeResult: SecurityResult<Unit> = SecurityResult.Unavailable,
) : SecurityRepository {
    var lastStepUpPassword: String? = null
    var lastVerifiedCode: String? = null
    var enrollStartCalls: Int = 0
    val revokedTokenIds = mutableListOf<String>()

    override suspend fun status(): SecurityResult<MfaStatus> = statusResult
    override suspend fun stepUp(password: String): SecurityResult<Unit> {
        lastStepUpPassword = password
        return stepUpResult
    }

    override suspend fun enrollStart(): SecurityResult<TotpEnrollment> {
        enrollStartCalls++
        return enrollStartResult
    }

    override suspend fun enrollVerify(code: String): SecurityResult<List<String>> {
        lastVerifiedCode = code
        return enrollVerifyResult
    }

    override suspend fun addEmailBackup(): SecurityResult<Unit> = backupAddResult
    override suspend fun removeEmailBackup(): SecurityResult<Unit> = backupRemoveResult
    override suspend fun disableMfa(): SecurityResult<Unit> = disableResult
    override suspend fun connectedDevices(): SecurityResult<List<ConnectedDevice>> = devicesResult
    override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> {
        revokedTokenIds += tokenId
        return revokeResult
    }
}

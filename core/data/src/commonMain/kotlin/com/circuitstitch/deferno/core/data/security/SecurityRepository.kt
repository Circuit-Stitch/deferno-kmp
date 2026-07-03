package com.circuitstitch.deferno.core.data.security

import com.circuitstitch.deferno.core.data.account.AccountContext
import com.circuitstitch.deferno.core.data.account.ReauthRequester
import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment

/**
 * The Security & 2FA read/write seam the Settings slice drives — [SecurityRemoteSource]'s surface,
 * with the same per-Account re-auth routing [com.circuitstitch.deferno.core.data.auth.DefaultAuthRepository]
 * gives `/auth/me`: a [SecurityResult.Unauthorized] (expired/invalid PAT) raises a re-auth request
 * scoped to the Active Account as a side effect, and the result is returned unchanged for the
 * component to render. Online-only by nature — factors and device tokens are server truth with no
 * local cache (like the identity read, "fetched live per scene").
 *
 * [Inert] is the unwired-host/test default: every call reports [SecurityResult.Unavailable], so a
 * host that doesn't supply the seam renders the screen's unavailable state rather than crashing.
 */
interface SecurityRepository {
    suspend fun status(): SecurityResult<MfaStatus>
    suspend fun stepUp(password: String): SecurityResult<Unit>
    suspend fun enrollStart(): SecurityResult<TotpEnrollment>
    suspend fun enrollVerify(code: String): SecurityResult<List<String>>
    suspend fun addEmailBackup(): SecurityResult<Unit>
    suspend fun removeEmailBackup(): SecurityResult<Unit>
    suspend fun disableMfa(): SecurityResult<Unit>
    suspend fun connectedDevices(): SecurityResult<List<ConnectedDevice>>
    suspend fun revokeDevice(tokenId: String): SecurityResult<Unit>

    companion object {
        val Inert: SecurityRepository = object : SecurityRepository {
            override suspend fun status(): SecurityResult<MfaStatus> = SecurityResult.Unavailable
            override suspend fun stepUp(password: String): SecurityResult<Unit> = SecurityResult.Unavailable
            override suspend fun enrollStart(): SecurityResult<TotpEnrollment> = SecurityResult.Unavailable
            override suspend fun enrollVerify(code: String): SecurityResult<List<String>> =
                SecurityResult.Unavailable
            override suspend fun addEmailBackup(): SecurityResult<Unit> = SecurityResult.Unavailable
            override suspend fun removeEmailBackup(): SecurityResult<Unit> = SecurityResult.Unavailable
            override suspend fun disableMfa(): SecurityResult<Unit> = SecurityResult.Unavailable
            override suspend fun connectedDevices(): SecurityResult<List<ConnectedDevice>> =
                SecurityResult.Unavailable
            override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> =
                SecurityResult.Unavailable
        }
    }
}

/**
 * Default [SecurityRepository]: delegates to the [remoteSource] and routes a [SecurityResult.Unauthorized]
 * to per-Account re-auth — resolving who is active *now* through the read-only [AccountContext] so a
 * 401 can never sign out a sibling Account (ADR-0002). Step-up's own "wrong password" comes back as
 * [SecurityResult.Rejected], not Unauthorized, so it never trips this.
 */
class DefaultSecurityRepository(
    private val remoteSource: SecurityRemoteSource,
    private val accountContext: AccountContext,
    private val reauth: ReauthRequester,
) : SecurityRepository {

    override suspend fun status(): SecurityResult<MfaStatus> = remoteSource.fetchStatus().raising()
    override suspend fun stepUp(password: String): SecurityResult<Unit> = remoteSource.stepUp(password).raising()
    override suspend fun enrollStart(): SecurityResult<TotpEnrollment> = remoteSource.enrollStart().raising()
    override suspend fun enrollVerify(code: String): SecurityResult<List<String>> =
        remoteSource.enrollVerify(code).raising()
    override suspend fun addEmailBackup(): SecurityResult<Unit> = remoteSource.addEmailBackup().raising()
    override suspend fun removeEmailBackup(): SecurityResult<Unit> = remoteSource.removeEmailBackup().raising()
    override suspend fun disableMfa(): SecurityResult<Unit> = remoteSource.disableMfa().raising()
    override suspend fun connectedDevices(): SecurityResult<List<ConnectedDevice>> =
        remoteSource.fetchConnectedDevices().raising()
    override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> =
        remoteSource.revokeDevice(tokenId).raising()

    private fun <T> SecurityResult<T>.raising(): SecurityResult<T> {
        if (this is SecurityResult.Unauthorized) {
            accountContext.activeAccount.value?.let { account -> reauth.requestReauth(account.id) }
        }
        return this
    }
}

package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult

/**
 * Test [NativeAuthRemoteSource] for the browser sign-in orchestration. Returns programmed
 * register/exchange outcomes, builds a deterministic authorize URL (echoing the PKCE challenge + state
 * so a fake browser can reflect them), and records what it was called with.
 */
class FakeNativeAuthRemoteSource : NativeAuthRemoteSource {

    var registerResult: ApiResult<ClientRegistration> = ApiResult.Success(ClientRegistration("client-1"))
    var exchangeResult: ApiResult<MintedToken> = ApiResult.Success(MintedToken(token = "minted-pat", tokenId = "tok-id-1"))

    var registerCalls = 0
        private set
    var exchangeCalls = 0
        private set
    var lastCode: String? = null
        private set
    var lastVerifier: String? = null
        private set
    var lastExchangeRedirectUri: String? = null
        private set

    override suspend fun register(redirectUri: String, clientName: String): ApiResult<ClientRegistration> {
        registerCalls++
        return registerResult
    }

    override fun authorizeUrl(clientId: String, redirectUri: String, codeChallenge: String, state: String): String =
        "https://auth.test/authorize?client_id=$clientId&redirect_uri=$redirectUri" +
            "&response_type=code&code_challenge=$codeChallenge&code_challenge_method=S256&state=$state"

    override suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String,
        deviceName: String,
    ): ApiResult<MintedToken> {
        exchangeCalls++
        lastCode = code
        lastVerifier = codeVerifier
        lastExchangeRedirectUri = redirectUri
        return exchangeResult
    }

    companion object {
        fun failure(status: Int = 503): ApiResult.Failure =
            ApiResult.Failure(ApiError.Status(status, "unavailable"))
    }
}

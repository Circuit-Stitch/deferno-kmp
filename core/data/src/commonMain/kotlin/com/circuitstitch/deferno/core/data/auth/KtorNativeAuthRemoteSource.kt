package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.network.dto.ClientRegistrationRequest
import com.circuitstitch.deferno.core.network.dto.ClientRegistrationResponse
import com.circuitstitch.deferno.core.network.dto.CreateApiTokenResponseDto
import com.circuitstitch.deferno.core.network.dto.NativeTokenRequest
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * Production [NativeAuthRemoteSource] over the shared Deferno [HttpClient] (#15, ADR-0026). The
 * register / authorize / token endpoints sit under the configured `auth/native/…` path on the `/api/`
 * base ([environment]); register + exchange POST through the standard `requestApi` envelope-unwrapping
 * pipeline, and [authorizeUrl] is built off the same base. No bearer is attached — these run before any
 * Account exists (`DefernoHttpClient`).
 */
class KtorNativeAuthRemoteSource(
    private val client: HttpClient,
    private val environment: DefernoEnvironment,
) : NativeAuthRemoteSource {

    override suspend fun register(redirectUri: String, clientName: String): ApiResult<ClientRegistration> =
        client.requestApi<ClientRegistrationResponse> {
            method = HttpMethod.Post
            url { appendPathSegments("auth", "native", "register") }
            contentType(ContentType.Application.Json)
            setBody(ClientRegistrationRequest(redirectUris = listOf(redirectUri), clientName = clientName))
        }.mapData { ClientRegistration(it.clientId) }

    override fun authorizeUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
    ): String = URLBuilder(environment.baseUrl).apply {
        appendPathSegments("auth", "native", "authorize")
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("response_type", "code")
        parameters.append("code_challenge", codeChallenge)
        parameters.append("code_challenge_method", "S256")
        parameters.append("state", state)
    }.buildString()

    override suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String,
        deviceName: String,
    ): ApiResult<MintedToken> =
        client.requestApi<CreateApiTokenResponseDto> {
            method = HttpMethod.Post
            url { appendPathSegments("auth", "native", "token") }
            contentType(ContentType.Application.Json)
            setBody(
                NativeTokenRequest(
                    code = code,
                    codeVerifier = codeVerifier,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    name = deviceName,
                ),
            )
        }.mapData { MintedToken(token = it.token, tokenId = it.id) }
}

/** Maps an [ApiResult] success payload through [transform], leaving a failure untouched. */
private inline fun <T, R> ApiResult<T>.mapData(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> ApiResult.Failure(error)
}

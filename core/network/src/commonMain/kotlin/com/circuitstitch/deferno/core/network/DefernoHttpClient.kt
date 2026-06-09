package com.circuitstitch.deferno.core.network

import com.circuitstitch.deferno.core.network.platform.platformHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Raised when a request would send cleartext (non-HTTPS) traffic to a non-loopback host —
 * blocked by the client's cleartext guard before it leaves the device (issue #17, ADR-0009).
 * Surfaces to callers as [ApiError.Transport] when they go through [requestApi].
 */
class CleartextNotPermittedException(host: String) : Exception(
    "Cleartext (non-HTTPS) traffic to '$host' is not permitted; only HTTPS and loopback hosts are allowed.",
)

/**
 * Builds the production Deferno [HttpClient] for [environment] (issue #17): the shared Ktor
 * client over the platform engine (OkHttp / Darwin), with JSON content negotiation, the Active
 * Account bearer from [tokenProvider] attached per request, the cleartext guard, and `2xx`/error
 * responses left for [requestApi] to map into [ApiResult] (it does not throw on HTTP status).
 *
 * The client is engine-agnostic; tests build the same configuration over Ktor's `MockEngine`
 * via the internal [defernoHttpClient] overload.
 */
fun DefernoHttpClient(
    environment: DefernoEnvironment,
    tokenProvider: BearerTokenProvider,
): HttpClient = defernoHttpClient(platformHttpClientEngine(), environment, tokenProvider)

/**
 * The engine-injectable client builder shared by production ([DefernoHttpClient], real engine)
 * and tests (`MockEngine`). Keeping the configuration in one place means the MockEngine tests
 * exercise the *same* auth + cleartext + content-negotiation pipeline that ships.
 */
internal fun defernoHttpClient(
    engine: HttpClientEngine,
    environment: DefernoEnvironment,
    tokenProvider: BearerTokenProvider,
): HttpClient = HttpClient(engine) {
    // We map every HTTP status ourselves into ApiResult (including non-2xx → ApiError); never
    // let Ktor throw on status. `false` is also Ktor's default — set explicitly for intent.
    expectSuccess = false

    install(ContentNegotiation) {
        json(DefernoJson)
    }

    // Base URL: requests build their path onto it with `url { appendPathSegments(...) }`.
    defaultRequest {
        url(environment.baseUrl)
    }

    install(CleartextGuard)
    install(bearerAuthPlugin(tokenProvider))
}

/**
 * Attaches `Authorization: Bearer <pat>` from [tokenProvider], read **fresh on every request**
 * so switching the Active Account re-points the credential immediately (ADR-0002). When the
 * provider returns `null` (no Account active) the request goes out unauthenticated — the path
 * the bootstrap calls take before a PAT exists (ADR-0012).
 *
 * A request that has **already set `Authorization`** is left untouched: sign-in validates a
 * *candidate* PAT with `GET /auth/me` carrying that token as an explicit bearer (#15, ADR-0023),
 * which must not be overridden by the Active Account's PAT (the precedence that also makes
 * add-account work while another Account is active).
 */
private fun bearerAuthPlugin(tokenProvider: BearerTokenProvider) =
    createClientPlugin("DefernoBearerAuth") {
        onRequest { request, _ ->
            if (request.headers[HttpHeaders.Authorization] == null) {
                tokenProvider.currentToken()?.let { token -> request.bearerAuth(token) }
            }
        }
    }

/**
 * Rejects cleartext (non-HTTPS) traffic to any non-loopback host before it is sent — TLS
 * enforced at request time as defense in depth on top of the https-by-construction
 * [DefernoEnvironment] base URLs (issue #17). Loopback cleartext is allowed for local backends.
 */
private val CleartextGuard = createClientPlugin("CleartextGuard") {
    onRequest { request, _ ->
        if (request.url.protocol.isCleartext() && !isLoopbackHost(request.url.host)) {
            throw CleartextNotPermittedException(request.url.host)
        }
    }
}

private fun URLProtocol.isCleartext(): Boolean = this != URLProtocol.HTTPS && this != URLProtocol.WSS

private fun isLoopbackHost(host: String): Boolean {
    // Ktor carries an IPv6 literal host bracketed (e.g. `[::1]`), so strip brackets before
    // matching — otherwise the IPv6-loopback exemption would never fire.
    val bare = host.removeSurrounding("[", "]")
    return bare == "localhost" || bare == "127.0.0.1" || bare == "::1"
}

/**
 * Issues a request and maps its response into a typed [ApiResult] (issue #17): the `Envelope<T>`
 * payload on success, or a typed [ApiError] otherwise. The single entry point the data layer
 * calls; [block] configures the request (method, path via `url { appendPathSegments(...) }`,
 * body, …) — the default is a `GET` to the base URL.
 *
 * `T` is the **payload** type (the `data` field), not the envelope; the envelope is unwrapped
 * here. `T` must be `@Serializable`.
 */
suspend inline fun <reified T> HttpClient.requestApi(
    noinline block: HttpRequestBuilder.() -> Unit = {},
): ApiResult<T> = executeApi(serializer<Envelope<T>>(), block)

@PublishedApi
internal suspend fun <T> HttpClient.executeApi(
    envelopeSerializer: KSerializer<Envelope<T>>,
    block: HttpRequestBuilder.() -> Unit,
): ApiResult<T> {
    val response = try {
        request(block)
    } catch (e: CancellationException) {
        throw e // never swallow coroutine cancellation
    } catch (e: Throwable) {
        // No usable response: connection / timeout / TLS, or a blocked cleartext request.
        return ApiResult.Failure(ApiError.Transport(e))
    }
    return response.toApiResult(envelopeSerializer)
}

private suspend fun <T> HttpResponse.toApiResult(
    envelopeSerializer: KSerializer<Envelope<T>>,
): ApiResult<T> {
    val statusCode = status.value
    val body = try {
        bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        return ApiResult.Failure(ApiError.Transport(e))
    }

    if (status.isSuccess()) {
        // ADR-0005: read the version BEFORE binding `data`, so an out-of-window response is
        // refused as UnsupportedVersion even when the breaking bump also changed the payload
        // shape (the realistic case the gate exists for). The probe ignores `data` entirely
        // (DefernoJson.ignoreUnknownKeys), so a changed/unparseable `data` shape can't pre-empt
        // the version check. A truly malformed body (no version) is a Transport failure.
        val rawVersion = try {
            DefernoJson.decodeFromString(VersionProbe.serializer(), body).version
        } catch (e: SerializationException) {
            return ApiResult.Failure(ApiError.Transport(e))
        }
        // ADR-0005 out-of-window policy, with the typed signal #18 needs: above MAX is an unknown
        // breaking major → ForceUpgrade; below MIN is too old → Unsupported; not major.minor at all
        // → Unparseable. The Kind lets a caller force-upgrade vs degrade vs treat-as-malformed.
        val version = ApiVersion.parseOrNull(rawVersion)
        if (version == null) {
            return ApiResult.Failure(
                ApiError.UnsupportedVersion(rawVersion, ApiError.UnsupportedVersion.Kind.Unparseable),
            )
        }
        if (!SupportedApiVersions.supports(version)) {
            val kind = if (version > SupportedApiVersions.MAX) {
                ApiError.UnsupportedVersion.Kind.ForceUpgrade
            } else {
                ApiError.UnsupportedVersion.Kind.Unsupported
            }
            return ApiResult.Failure(ApiError.UnsupportedVersion(rawVersion, kind))
        }
        // In-window: now bind `data`. A failure here is a genuinely malformed in-window body.
        val envelope = try {
            DefernoJson.decodeFromString(envelopeSerializer, body)
        } catch (e: SerializationException) {
            return ApiResult.Failure(ApiError.Transport(e))
        }
        return ApiResult.Success(envelope.data)
    }

    // Non-2xx. Parse the ErrorEnvelope when a body is present + parseable; otherwise synthesize
    // from the HTTP status — the path the verified empty `401` takes (CONTRACT-NOTES.md).
    if (body.isNotBlank()) {
        val parsed = try {
            DefernoJson.decodeFromString(ErrorEnvelope.serializer(), body)
        } catch (e: SerializationException) {
            null
        }
        if (parsed != null) {
            return ApiResult.Failure(ApiError.Endpoint(statusCode, parsed.error.code, parsed.error.message))
        }
    }
    return ApiResult.Failure(ApiError.Status(statusCode, status.description))
}

/**
 * A version-only view of a success envelope. Decoded with [DefernoJson] (`ignoreUnknownKeys`),
 * it reads `version` while ignoring `data` whatever its shape — so the ADR-0005 version gate can
 * run before `data: T` is bound (see [toApiResult]).
 */
@Serializable
private class VersionProbe(val version: String)

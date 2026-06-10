package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.url
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [AuthRemoteSource] over the shared Deferno [HttpClient] (#20). It pulls
 * `GET /auth/me`, runs the response through the same `requestApi` pipeline every endpoint uses —
 * the bearer plugin (Active Account PAT), the tolerant reader, the envelope unwrap, and the
 * ADR-0005 version gate — then condenses the typed [ApiResult] into a [MeResult]:
 *
 * - [ApiResult.Success] → [MeResult.Authenticated], the [AuthenticatedUserDto] mapped to the domain
 *   [com.circuitstitch.deferno.core.model.User] at the boundary (#18 mapper, ADR-0011).
 * - an HTTP `401` (whether the verified empty-body [ApiError.Status] or a structured
 *   [ApiError.Endpoint]) → [MeResult.Unauthorized] — the PAT is invalid/expired.
 * - anything else (transport/TLS, a non-401 server error, an unsupported envelope version) →
 *   [MeResult.Unavailable]: transient, not a credential problem.
 */
class KtorAuthRemoteSource(
    private val client: HttpClient,
) : AuthRemoteSource {

    override suspend fun fetchMe(): MeResult = requestMe { }

    // The candidate-token sign-in validation (#15, ADR-0023): set Authorization explicitly so the
    // bearer plugin leaves it alone — the pasted PAT is verified before any Account holds it.
    override suspend fun fetchMe(token: String): MeResult = requestMe { bearerAuth(token) }

    // Revoke bypasses `requestApi` deliberately: a 204 No Content has no envelope to unwrap (the
    // version-probe would treat the empty body as malformed). The token being revoked is sent as an
    // explicit bearer so the plugin leaves it untouched (it authenticates the very token it deletes).
    override suspend fun revokeToken(tokenId: String, token: String): Boolean = try {
        val response = client.delete {
            url { appendPathSegments("auth", "tokens", tokenId) }
            bearerAuth(token)
        }
        // 204 (revoked) or 200; a 404 (already gone) is effectively success but we report it as
        // unrevoked — the caller only logs the outcome, never blocks on it.
        response.status.isSuccess() || response.status == HttpStatusCode.NoContent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false // offline / transport — best-effort, local wipe still proceeds (ADR-0026)
    }

    private suspend fun requestMe(configure: HttpRequestBuilder.() -> Unit): MeResult {
        val result = client.requestApi<AuthenticatedUserDto> {
            url { appendPathSegments("auth", "me") }
            configure()
        }
        return when (result) {
            is ApiResult.Success -> MeResult.Authenticated(result.data.toDomain())
            is ApiResult.Failure ->
                if (result.error.isUnauthorized()) MeResult.Unauthorized else MeResult.Unavailable
        }
    }
}

/**
 * Whether [this] failure is an HTTP `401`. The verified empty-body 401 surfaces as [ApiError.Status]
 * (synthesized from the status when there is no error body, CONTRACT-NOTES → "Error model"); a
 * structured 401 body would surface as [ApiError.Endpoint]. Both mean "the PAT is no longer valid";
 * every other failure shape ([ApiError.Transport], [ApiError.UnsupportedVersion]) is *not* a
 * credential problem and must not trigger re-auth.
 */
private fun ApiError.isUnauthorized(): Boolean = when (this) {
    is ApiError.Status -> status == 401
    is ApiError.Endpoint -> status == 401
    is ApiError.Transport, is ApiError.UnsupportedVersion -> false
}

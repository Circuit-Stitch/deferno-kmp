package com.circuitstitch.deferno.core.data.security

import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ApiTokenViewDto
import com.circuitstitch.deferno.core.network.dto.MfaEnrollStartDto
import com.circuitstitch.deferno.core.network.dto.MfaEnrollVerifyDto
import com.circuitstitch.deferno.core.network.dto.MfaEnrollVerifyRequest
import com.circuitstitch.deferno.core.network.dto.MfaStatusDto
import com.circuitstitch.deferno.core.network.dto.StepUpRequest
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [SecurityRemoteSource] over the shared Deferno [HttpClient]. Reads run through the
 * standard `requestApi` pipeline (bearer plugin, tolerant reader, envelope unwrap, ADR-0005 version
 * gate) and condense the typed [ApiResult] into a [SecurityResult].
 *
 * **The step-up cookie.** The backend's step-up freshness gate is a web-session concept: a
 * successful `POST /auth/step-up` stamps the *session* and returns it as a `Set-Cookie`. The shared
 * client keeps no cookie jar (deliberately — a session cookie must never ride along on ordinary
 * data calls, and a step-up stamp is not user-bound server-side, so an app-wide jar could leak a
 * fresh stamp across an Account switch). Instead this source captures the raw `Set-Cookie` pairs
 * from the step-up response and echoes them — only on the `/auth/mfa/…` mutations — via an explicit
 * `Cookie` header. Held in memory only; the source is AccountScope, so an Account switch discards it.
 * Bearer-authenticated requests are exempt from the backend's CSRF header check, so no
 * `X-Requested-With` is needed.
 */
class KtorSecurityRemoteSource(
    private val client: HttpClient,
) : SecurityRemoteSource {

    // The raw `name=value` pairs from the last successful step-up (exact echo — no re-encoding, so
    // the opaque session value round-trips byte-identically). Keyed by name so a rotated session
    // cookie replaces its predecessor instead of stacking.
    private val stepUpCookies = mutableMapOf<String, String>()

    override suspend fun fetchStatus(): SecurityResult<MfaStatus> =
        client.requestApi<MfaStatusDto> {
            url { appendPathSegments("auth", "mfa", "status") }
        }.condense { it.toDomain() }

    override suspend fun stepUp(password: String): SecurityResult<Unit> {
        // Direct call, not requestApi: the security outcome here is the response's `Set-Cookie`
        // (the freshness stamp), which the envelope pipeline doesn't expose. The body's
        // `stepped_up_at` carries nothing the client needs — the server enforces the window.
        val response = try {
            client.post {
                url { appendPathSegments("auth", "step-up") }
                contentType(ContentType.Application.Json)
                setBody(StepUpRequest(password))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SecurityResult.Unavailable
        }
        return when {
            response.status.isSuccess() -> {
                response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { raw ->
                    val pair = raw.substringBefore(';')
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) stepUpCookies[name] = pair.substringAfter('=')
                }
                SecurityResult.Success(Unit)
            }
            // Step-up's 401 is "wrong password" (or budget exhausted) — the PAT itself already
            // authenticated to reach the handler. NOT a re-auth signal.
            response.status == HttpStatusCode.Unauthorized -> SecurityResult.Rejected
            else -> SecurityResult.Unavailable
        }
    }

    override suspend fun enrollStart(): SecurityResult<TotpEnrollment> =
        client.requestApi<MfaEnrollStartDto> {
            gatedPost("enroll", "start")
        }.condense { it.toDomain() }

    override suspend fun enrollVerify(code: String): SecurityResult<List<String>> =
        client.requestApi<MfaEnrollVerifyDto> {
            gatedPost("enroll", "verify")
            contentType(ContentType.Application.Json)
            setBody(MfaEnrollVerifyRequest(code))
        }.condense(rejectedStatus = 400) { it.recoveryCodes }

    override suspend fun addEmailBackup(): SecurityResult<Unit> =
        client.requestApi<kotlinx.serialization.json.JsonObject> {
            gatedPost("backup", "add")
        }.condense { }

    override suspend fun removeEmailBackup(): SecurityResult<Unit> =
        client.requestApi<kotlinx.serialization.json.JsonObject> {
            gatedPost("backup", "remove")
        }.condense { }

    override suspend fun disableMfa(): SecurityResult<Unit> =
        client.requestApi<kotlinx.serialization.json.JsonObject> {
            gatedPost("disable")
        }.condense { }

    override suspend fun fetchConnectedDevices(): SecurityResult<List<ConnectedDevice>> =
        client.requestApi<List<ApiTokenViewDto>> {
            url { appendPathSegments("auth", "connected-devices") }
        }.condense { list -> list.map { it.toDomain() } }

    // Like the sign-out self-revoke, this bypasses requestApi: a 204 has no envelope to unwrap.
    // Unlike it, no explicit bearer is set — the plugin attaches the Active Account's PAT, which is
    // what authorizes revoking a *different* device's token.
    override suspend fun revokeDevice(tokenId: String): SecurityResult<Unit> = try {
        val response = client.delete {
            url { appendPathSegments("auth", "tokens", tokenId) }
        }
        when {
            // 404 = already revoked — the outcome the caller wanted; converging beats erroring.
            response.status.isSuccess() || response.status == HttpStatusCode.NotFound ->
                SecurityResult.Success(Unit)
            response.status == HttpStatusCode.Unauthorized -> SecurityResult.Unauthorized
            else -> SecurityResult.Unavailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        SecurityResult.Unavailable
    }

    /** A step-up-gated `/auth/mfa/...` POST: method + path + the echoed step-up session cookie. */
    private fun HttpRequestBuilder.gatedPost(vararg segments: String) {
        method = HttpMethod.Post
        url { appendPathSegments("auth", "mfa", *segments) }
        if (stepUpCookies.isNotEmpty()) {
            header(HttpHeaders.Cookie, stepUpCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
    }

    /**
     * Condense the wire [ApiResult] to a [SecurityResult]: the 403 `step_up_required` refusal gets
     * its own typed case (the flow's resume signal); [rejectedStatus] is the one input-refusal
     * status a given call can produce (enroll-verify's `400 invalid code`); a `401` on these calls
     * *is* a PAT problem (unlike step-up's); everything else is transient.
     */
    private inline fun <T, R> ApiResult<T>.condense(
        rejectedStatus: Int? = null,
        map: (T) -> R,
    ): SecurityResult<R> = when (this) {
        is ApiResult.Success -> SecurityResult.Success(map(data))
        is ApiResult.Failure -> when {
            error.isStepUpRequired() -> SecurityResult.StepUpRequired
            rejectedStatus != null && error.status() == rejectedStatus -> SecurityResult.Rejected
            error.status() == 401 -> SecurityResult.Unauthorized
            else -> SecurityResult.Unavailable
        }
    }
}

private fun ApiError.status(): Int? = when (this) {
    is ApiError.Status -> status
    is ApiError.Endpoint -> status
    is ApiError.Transport, is ApiError.UnsupportedVersion -> null
}

/** The 403 whose envelope code is `step_up_required` — the typed "re-verify your password" signal. */
private fun ApiError.isStepUpRequired(): Boolean =
    this is ApiError.Endpoint && status == 403 && code == "step_up_required"

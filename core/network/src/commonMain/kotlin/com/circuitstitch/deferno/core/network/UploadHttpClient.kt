package com.circuitstitch.deferno.core.network

import com.circuitstitch.deferno.core.network.platform.platformHttpClientEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * A **bare** HttpClient for presigned-URL uploads (#375). Unlike the API [DefernoHttpClient] it has
 * **no API base URL** (the PUT goes to a full presigned S3/LocalFs URL) and **no bearer auth plugin**:
 * an S3 presigned URL is SigV4 *query*-signed, so an extra `Authorization` header — which the API
 * client attaches on every request — would make S3 reject the PUT. Wrapped in a dedicated type so the
 * DI graph tells it apart from the singular API [HttpClient].
 *
 * The caller sends the presign response's `headers` map byte-exact (the SSE-KMS pair + content-type
 * S3 signed into the URL); a PUT without them is a guaranteed `403 SignatureDoesNotMatch`. The map is
 * empty in LocalFs (dev) mode, so the bare PUT just works there.
 */
class UploadHttpClient(val client: HttpClient)

/** Builds the bare upload client over the platform engine — no base URL, no auth (see [UploadHttpClient]). */
internal fun buildUploadHttpClient(engine: HttpClientEngine = platformHttpClientEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
    }

package com.circuitstitch.deferno.core.secure

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An RFC 7636 PKCE pair for the native browser sign-in flow (ADR-0012/0026): the secret [verifier]
 * the client keeps and later proves possession of at token exchange, and the [challenge] it sends to
 * the authorization endpoint up front. Bound together so a caller can't accidentally pair a challenge
 * with the wrong verifier.
 */
data class PkcePair(val verifier: String, val challenge: String)

/**
 * Generates PKCE material (RFC 7636) and CSRF nonces for the native sign-in flow. The verifier is 32
 * bytes of CSPRNG entropy base64url-encoded (43 chars, all in the unreserved set); the S256 [challenge]
 * is `base64url(SHA-256(ascii(verifier)))`. Both are **unpadded**, as the RFC requires. The crypto
 * primitives are platform-provided ([secureRandomBytes] / [sha256], `expect`/`actual` like
 * `core:network`'s `PlatformHttpClientEngine`); this composition is shared across every target.
 *
 * Holds no state and logs nothing (ADR-0009) — the verifier is a transient secret the sign-in
 * orchestration passes straight into the token exchange and discards.
 */
@OptIn(ExperimentalEncodingApi::class)
object Pkce {
    // base64url, no padding — the alphabet (A–Z a–z 0–9 - _) is a subset of RFC 7636's unreserved set,
    // and PKCE forbids the '=' padding that plain/MIME Base64 would add.
    private val base64Url: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** A fresh verifier + its S256 challenge. */
    fun generate(): PkcePair {
        val verifier = base64Url.encode(secureRandomBytes(VERIFIER_ENTROPY_BYTES))
        return PkcePair(verifier, challengeFor(verifier))
    }

    /** The S256 transform `base64url(SHA-256(ascii([verifier])))` — split out so it is unit-testable
     *  against the RFC 7636 known-answer vector independent of the CSPRNG. */
    fun challengeFor(verifier: String): String = base64Url.encode(sha256(verifier.encodeToByteArray()))

    /** A URL-safe random token for the OAuth `state` CSRF nonce — [byteCount] bytes of entropy,
     *  base64url-encoded (unpadded), safe to drop straight into a query string. */
    fun randomUrlSafe(byteCount: Int = STATE_ENTROPY_BYTES): String =
        base64Url.encode(secureRandomBytes(byteCount))

    private const val VERIFIER_ENTROPY_BYTES = 32
    private const val STATE_ENTROPY_BYTES = 32
}

/** [byteCount] bytes from the platform CSPRNG (Android/JVM `SecureRandom`, iOS `SecRandomCopyBytes`). */
internal expect fun secureRandomBytes(byteCount: Int): ByteArray

/** The SHA-256 digest of [input] (Android/JVM `MessageDigest`, iOS CommonCrypto `CC_SHA256`). */
internal expect fun sha256(input: ByteArray): ByteArray

package com.circuitstitch.deferno.core.secure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PKCE material generation (RFC 7636, ADR-0026). Runs on the pure-JVM/Android-host fast path
 * (ADR-0006) — exercising the JVM `MessageDigest`/`SecureRandom` actuals; the iOS CommonCrypto
 * actuals are covered by the native test run on macOS.
 */
class PkceTest {

    // RFC 7636 §4 / Appendix B known-answer vector — pins the S256 transform independent of the CSPRNG.
    private val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val rfcChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    fun s256_challenge_matches_rfc7636_vector() {
        assertEquals(rfcChallenge, Pkce.challengeFor(rfcVerifier))
    }

    @Test
    fun generated_verifier_is_43_chars_in_the_unreserved_set() {
        val (verifier, _) = Pkce.generate()
        // 32 bytes, base64url, no padding → exactly 43 characters.
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it in BASE64URL }, "verifier has a non-base64url char: $verifier")
    }

    @Test
    fun generated_challenge_is_the_s256_of_its_verifier_and_unpadded() {
        val (verifier, challenge) = Pkce.generate()
        assertEquals(Pkce.challengeFor(verifier), challenge)
        // base64url, unpadded — never the '=' / '+' / '/' of standard Base64.
        assertTrue(challenge.none { it == '=' || it == '+' || it == '/' }, challenge)
    }

    @Test
    fun successive_pairs_are_unique() {
        assertNotEquals(Pkce.generate().verifier, Pkce.generate().verifier)
    }

    @Test
    fun random_url_safe_is_unpadded_and_unique() {
        val a = Pkce.randomUrlSafe()
        val b = Pkce.randomUrlSafe()
        assertNotEquals(a, b)
        assertTrue(a.all { it in BASE64URL }, a)
    }

    private companion object {
        val BASE64URL: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_').toSet()
    }
}

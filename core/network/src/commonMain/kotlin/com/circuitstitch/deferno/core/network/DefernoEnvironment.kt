package com.circuitstitch.deferno.core.network

/**
 * Which Deferno backend the client talks to — the base URL every request resolves against
 * (issue #17). The set is closed and the URLs are baked in (from the spec `servers` block +
 * the staging URL verified on 2026-06-06, see `contracts/CONTRACT-NOTES.md`), so a request
 * can only ever target a known host: there is no free-form "base URL" string a caller could
 * point at an attacker-controlled or cleartext endpoint.
 *
 * **TLS is enforced by construction:** [Production] and [Staging] are `https`; the only
 * cleartext URL is [Local], whose host is loopback (`localhost`) — the one place a developer
 * runs the backend without certs. The [CleartextGuard] in [defernoHttpClient] re-checks this
 * at request time as defense in depth (so even a hand-built non-HTTPS request to a remote host
 * is rejected), satisfying issue #17's "cleartext disabled; HTTPS enforced".
 *
 * Callers build endpoint paths *onto* [baseUrl] (it already carries the `/api` prefix) with
 * `url { appendPathSegments(...) }` rather than passing absolute strings, so the `/api` prefix
 * and host come from here and only the path is per-endpoint.
 */
enum class DefernoEnvironment(val baseUrl: String) {
    /** Production: `https://api.deferno.app/api` (spec `servers`). */
    Production("https://api.deferno.app/api"),

    /** Staging: `https://app2.defernowork.com/api` (verified, envelope `version: 0.1`). */
    Staging("https://app2.defernowork.com/api"),

    /** Local development: `http://localhost:3000/api` (loopback cleartext, spec `servers`). */
    Local("http://localhost:3000/api"),
}

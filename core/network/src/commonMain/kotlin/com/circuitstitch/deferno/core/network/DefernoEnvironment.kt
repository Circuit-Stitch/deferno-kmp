package com.circuitstitch.deferno.core.network

/**
 * Which Deferno backend the client talks to — the base URL every request resolves against
 * (issue #17). The set is closed and the URLs are baked in (staging verified 2026-06-06,
 * production verified 2026-06-10 — see `contracts/CONTRACT-NOTES.md`), so a request can
 * only ever target a known host: there is no free-form "base URL" string a caller could
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
 *
 * **The trailing slash is load-bearing.** Ktor's `defaultRequest { url(baseUrl) }` resolves each
 * request URL *relative* to [baseUrl] (RFC 3986 reference resolution). A base ending in `/api`
 * treats `api` as a file, so appending `auth/me` resolves to `…/auth/me` — the `/api` prefix is
 * silently dropped and the request hits the web frontend (it returns the SPA's HTML, not the API).
 * Ending the base in `/api/` makes `api/` a directory, so the path appends as `…/api/auth/me`.
 * Verified live against staging (#20); a regression test pins it in `DefernoHttpClientTest`.
 */
enum class DefernoEnvironment(val baseUrl: String) {
    /**
     * Production: `https://app.defernowork.com/api/` (verified live 2026-06-10: `/api/auth/me`
     * answers 401 like staging). The spec `servers` block's `api.deferno.app` does not resolve
     * in DNS and was never a real host. NOTE: the native browser sign-in endpoints
     * (`/api/auth/native/…`, ADR-0026) 404 on this host as of 2026-06-10 — browser sign-in
     * against Production needs the backend handoff (Deferno#299) deployed there first.
     */
    Production("https://app.defernowork.com/api/"),

    /** Staging: `https://app2.defernowork.com/api/` (verified, envelope `version: 0.1`). */
    Staging("https://app2.defernowork.com/api/"),

    /** Local development: `http://localhost:3000/api/` (loopback cleartext, spec `servers`). */
    Local("http://localhost:3000/api/"),
    ;

    companion object {
        /**
         * Resolve a build-injected environment name to the backend enum — the single home for how a
         * variant's env string becomes a [DefernoEnvironment] (ADR-0047). The name is injected per build
         * variant: Android reads `BuildConfig.DEFERNO_ENV` (a per-flavor `buildConfigField`), iOS reads
         * the `DefernoEnv` `Info.plist` value (a per-config build setting). Any unknown or absent value
         * fails safe to [Production] — the fail-safe backend for a misconfigured build.
         *
         * Matches on literal names (deliberately NOT [Enum.name]) so the selector is independent of R8
         * field renaming: it runs in the minified `prodRelease`/`stagingRelease` variants too.
         */
        fun fromName(name: String?): DefernoEnvironment = when (name) {
            "Staging" -> Staging
            "Local" -> Local
            else -> Production
        }
    }
}

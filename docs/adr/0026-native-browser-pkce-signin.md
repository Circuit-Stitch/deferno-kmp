# Native sign-in: system-browser OAuth Authorization Code + PKCE, minting a per-device PAT

**Context.** ADR-0012 established that the durable credential is a per-device **PAT** and that
OAuth is only the *bootstrap*; ADR-0023 shipped a **pasted-PAT** path as the v1 sign-in surface
because the backend browser→app token handoff (Kyle-Falconer/Deferno **#299**) was unfinished. #299
has now landed (the pinned `contracts/openapi-0.1.json` was refreshed against it), so the proper
flow is unblocked. The backend ADR `2026-06-09-native-client-browser-pkce-token-handoff` (Accepted)
makes Deferno a minimal OAuth 2.0 authorization server for first-party native clients. This ADR is
the **client half**: how the KMP app drives that flow.

**Decision.** Native sign-in is a **system-browser OAuth Authorization Code + PKCE** flow that mints
a `kind:user` PAT — the app ships **zero credential UI** (password + Deferno-owned MFA + external-IdP
SSO all happen in the browser; RFC 8252). The shared orchestration lives in `core/data`'s
`SignInService.signInWithBrowser` and reuses the existing `AccountManager.addAccount` convergence
seam (ADR-0012/0023), so a browser-minted and a pasted token produce an identical Account:

1. **Register** a public client (RFC 7591, no secret) at `POST /auth/native/register`, caching the
   `client_id` per install (`OAuthClientStore`; in-memory in v1, a persistent store is a follow-up).
2. Generate a PKCE pair (S256) + a CSRF `state` (`core/secure` `Pkce`, an `expect/actual` over
   `SecureRandom`/`MessageDigest` and iOS CommonCrypto — no new dependency).
3. Open the **system browser** at `GET /auth/native/authorize?client_id&redirect_uri&response_type=code
   &code_challenge&code_challenge_method=S256&state` via a per-platform `BrowserAuthenticator` and
   suspend until the redirect to the app's `redirect_uri` is captured.
4. Verify `state`, read the one-time `code`, and exchange it at `POST /auth/native/token
   {code, code_verifier, client_id, redirect_uri, name}` for the PAT (`CreateApiTokenResponse`).
5. Resolve identity (`GET /auth/me` → `AccountId` = backend User id, label) and commit via
   `addAccount`, carrying the token's **server-side id** so sign-out can revoke it.

The `redirect_uri` is platform-owned: a fixed custom scheme `com.circuitstitch.deferno://auth` on
mobile (a verified App/Universal Link is the preferred upgrade), a freshly-bound loopback
`http://127.0.0.1:{port}/callback` on desktop (the AS ignores the loopback port, RFC 8252 §7.3). The
`BrowserAuthenticator` is a `core/data` port with per-platform implementations: **Android** opens an
`ACTION_VIEW` intent and captures the redirect via a `singleTop` activity's `onNewIntent` →
`AuthRedirectInbox`; **desktop** binds a loopback `ServerSocket` + `Desktop.browse`; **iOS** opens
Safari via `UIApplication.openURL` and captures via the Swift URL handler → `IosAuthRedirectInbox`.

The pasted-PAT path (ADR-0023) is **retained as a developer affordance** only — hidden behind a
debug-only "Use a token instead" reveal (Android gates it on `BuildConfig.DEBUG`) — for local dev and
as an MFA escape hatch. It is no longer the primary surface.

**Considered & rejected.** *In-app credential form / ROPC against `/auth/sign-in`* — rejected (as in
the backend ADR): the whole point is the app never touches credentials, and a password form can't
serve MFA or external-IdP SSO. `/auth/sign-in` is the web login's own leg (it requires
`X-Requested-With` + an in-flight `auth_request`) and the client never calls it. *Depending on RFC 8414
discovery* (`/.well-known/oauth-authorization-server`) — rejected for v1: it's unreliable across
environments (local `503 OIDC not configured`; staging host-root serves the SPA), so endpoints are
built relative to the configured `/api/` base instead. *An embedded WebView* — rejected: it breaks
Google SSO + password managers and is a phishing surface (RFC 8252). *Custom Tabs /
`ASWebAuthenticationSession`* — preferred for UX (in-app tab, auto-dismiss on redirect) and a
follow-up; v1 uses the plain system browser, which is functionally equivalent and keeps a browser-UI
dependency out of `core/data`.

**Consequences.** Sign-out now does a **real server-side revoke** for browser-minted accounts: the
token's id is known at mint time and stored on `Account.tokenId` (a non-secret reference, persisted in
the roster), so `AccountManager.removeAccount` best-effort `DELETE /auth/tokens/{id}` before the local
wipe (closing the ADR-0023 "local-wipe-only" gap and Deferno#310). Pasted/dev accounts carry no token
id → local-wipe-only, so the shared dev PAT is never revoked. The `/auth/native/*` endpoints are
**not** in `openapi-0.1.json` (they're browser-redirect / RFC-standard, outside the data API); their
verified shapes live in `contracts/CONTRACT-NOTES.md` → "Native browser sign-in". **Residual risk
(ADR §Consequences in the backend ADR):** a custom-scheme redirect can be claimed by a malicious app —
PKCE stops it *exchanging* the code but not *receiving* the redirect; the mitigation is to prefer
verified App/Universal Links, a follow-up. **iOS is code-complete but device-verification is deferred
to macOS** (ADR-0006): the Swift layer must register the `com.circuitstitch.deferno` URL scheme
(`CFBundleURLTypes`) and forward incoming URLs to `IosAuthRedirectInbox.publish`, and
`ASWebAuthenticationSession` should be swapped in for `openURL` there.

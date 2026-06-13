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
`ACTION_VIEW` intent and captures the redirect via a `singleTop` activity's `onNewIntent`; **desktop**
binds a loopback `ServerSocket` + `Desktop.browse`; **iOS** opens Safari via `UIApplication.openURL`
and captures via the Swift URL handler. The mobile capture rendezvous is one shared `commonMain`
`AuthRedirectInbox` (#137) — an AppScope singleton injected into both authenticators and exposed on
the `AppComponent` for the host OS layer to publish into (Android's `MainActivity`, iOS's
`DefernoRoot.forwardAuthRedirect`), not ambient static state.

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
(`CFBundleURLTypes`) and forward incoming URLs via `DefernoRoot.forwardAuthRedirect` (#137), and
`ASWebAuthenticationSession` should be swapped in for `openURL` there.

**Amendment (2026-06-10).** The `ASWebAuthenticationSession` follow-up landed: `IosBrowserAuthenticator`
now runs the authorize leg in the in-app session sheet (presented over the app, auto-dismissing,
sharing Safari's cookies/password manager — still not a WebView), capturing its own redirect, after a
TestFlight build was observed bouncing users out to Safari. The anchor is provided from Kotlin
(`UIApplication.keyWindow`), so no Swift-layer swap was needed; the `CFBundleURLTypes` scheme +
`forwardAuthRedirect` → inbox path is retained as a fallback for externally-opened redirects (Android
is unchanged). Two environment fixes rode along: `DefernoEnvironment.Production` was corrected to the
real prod host `https://app.defernowork.com/api/` (the spec `servers` entry `api.deferno.app` never
resolved in DNS), and `DefernoRoot` now selects the environment by build configuration
(`Platform.isDebugBinary`: Debug → Staging, Release/TestFlight → Production). Note: as of 2026-06-10
the `/api/auth/native/*` endpoints 404 on the prod host — browser sign-in against Production needs
the backend handoff (Deferno#299) deployed there.

**Amendment (2026-06-13) — macOS uses a loopback redirect (real external browser), not
`ASWebAuthenticationSession` and not a custom scheme (#189).** macOS deliberately **diverges from iOS**:
`MacBrowserAuthenticator` is the twin of the JVM desktop **`LoopbackBrowserAuthenticator`**, not
`IosBrowserAuthenticator`. It opens the authorize URL in the user's **default browser**
(`NSWorkspace.openURL`) and captures the redirect on a **`127.0.0.1:{ephemeral}` loopback listener** (RFC
8252 §7.3 — a native POSIX-socket accept), the desktop `redirect_uri` the backend allows.

*Considered & rejected, in order:*

- *`ASWebAuthenticationSession` (the iOS choice).* On macOS it presents a **chromeless, mostly-empty
  separate window** plus, for a non-ephemeral session, a **cookie-consent prompt** before the user even
  sees the form — both wrong for a desktop, where users expect their **real browser** (full chrome,
  existing logged-in/SSO sessions, password manager). iOS adopted the session to avoid bouncing a *phone*
  user to Safari; on a desktop that bounce is the **expected** OAuth pattern (gh CLI, 1Password, Slack).
- *A `com.circuitstitch.deferno://` custom-scheme redirect (the Android mechanism) captured via
  `AuthRedirectInbox`.* Tried first; **rejected after testing**: on macOS the browser hands the
  custom-scheme URL to **LaunchServices**, which pops a Safari *"allow this site to open the app?"* prompt
  and **launches a second app process** instead of delivering the redirect to the running one — so the
  in-flight sign-in's inbox never sees it. (`LSMultipleInstancesProhibited` + a single `Window` scene
  reduce the spawning, but the prompt is intrinsic to custom schemes.) Loopback keeps LaunchServices out
  of the loop entirely: the redirect is just an HTTP request to localhost, the browser lands on a real
  "you can close this" page, and the running app is never left.

The custom-scheme registration (`CFBundleURLTypes`) + `forwardAuthRedirect` → inbox path is **retained as
a fallback** for an externally-opened redirect (as on iOS), but is not the primary capture path.

The one capability the external browser **loses** is `ASWebAuthenticationSession`'s close/cancel event: a
real browser gives the launching app no tab-close signal, so there is **no automatic `Cancelled`** on
this path — an abandoned sign-in would otherwise hang on the listener. Two mitigations: the loopback
`accept` self-frees after the backend's 5-min code TTL, and a shared **"Need to try again?" affordance**
(`SignInComponent.onRetry()`) cancels the stalled attempt and starts a fresh one. It renders where the
app stays foreground during sign-in — **macOS, Android, desktop** — but **not iOS**, whose modal session
sheet occludes the Auth screen and already cancels on close. A focus-based heuristic (reset on app
re-activation) was rejected as too fragile (Cmd-Tab back to copy a password would misfire).

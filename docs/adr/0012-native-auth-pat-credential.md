# Native auth: OAuth is the bootstrap, a personal access token is the credential

**Context.** The Deferno API exposes two security schemes — an opaque Bearer **personal access token
(PAT)** (`POST /auth/tokens`, described in the spec as *"the path native mobile clients use"*) and a
web `session_cookie`. There are **no `oauth2`/`openIdConnect` schemes**: OAuth/PKCE against Zitadel
lives entirely on the web/auth server, outside this API. So the in-app system-browser OAuth flow
(#15, gated on backend [Kyle-Falconer/Deferno#299](https://github.com/Kyle-Falconer/Deferno/issues/299))
yields a Zitadel session — **not** an API credential.

**Decision.** The client's **durable credential is a per-device PAT**, never OAuth tokens. The
system-browser OAuth flow is only a **bootstrap**: once it authenticates, the client calls
`POST /auth/tokens` to mint a **named** PAT (e.g. `"Deferno Android — Pixel 8"`), stores **only that**
in the per-Account `SecretVault` (ADR-0002), and discards the Zitadel access/refresh tokens. Every
request is then `Authorization: Bearer <pat>`; the client never talks to Zitadel again. The same
`AccountManager.addAccount(account, token)` seam serves both this path and the **manual-paste dev
path** (a developer mints a PAT in the web client's User Settings and pastes it) — they converge on
one credential. On logout / Account removal the client **revokes** the PAT server-side
(`DELETE /auth/tokens/{id}`) in addition to the local secure-wipe (ADR-0009).

**Considered & rejected.** Persisting Zitadel OAuth access + refresh tokens and managing refresh
in-client — the API is not an OAuth resource server in this spec, and PATs are opaque, long-lived and
individually revocable, which is far less client machinery and decouples the client from Zitadel after
bootstrap.

**Consequences.** PATs are long-lived, so a leak is valid until revoked — mitigated by device-bound
secure storage (ADR-0009), **per-device naming** for granular revocation (already supported in the web
client's PAT settings, and exposed in-client via `GET/POST/PATCH/DELETE /auth/tokens`), and
revoke-on-removal. This makes in-app PKCE (#15) a pure *no-paste minting UX* and keeps it off the
Phase-1 critical path (blocked only by Deferno#299). See `CONTEXT.md` → **Personal access token (PAT)**.

**Update (2026-06-10, Deferno#299 landed).** The system-browser bootstrap is now implemented as a
full **Authorization Code + PKCE** flow (`/auth/native/{register,authorize,token}`) that mints the PAT —
see **ADR-0026**, which supersedes the open question of *how* the bootstrap yields a credential. The
revoke-on-removal posited here is now real for browser-minted tokens: the token id is known at mint
time, stored on `Account.tokenId`, and `DELETE /auth/tokens/{id}` fires on sign-out.

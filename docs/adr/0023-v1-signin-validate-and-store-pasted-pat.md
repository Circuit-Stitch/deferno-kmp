# v1 sign-in: validate a pasted PAT against `/auth/me`, then store it

**Context.** #15's no-paste, system-browser OAuth + PKCE flow is gated on the backend
code→bearer exchange ([Kyle-Falconer/Deferno#299](https://github.com/Kyle-Falconer/Deferno#299)),
which is still **open** — so the flow the issue's acceptance criteria describe cannot run
end-to-end against a real server. ADR-0012 already established that the durable credential is a
per-device **PAT** and that a **manual-paste** path converges with the future browser-mint path on
the one `AccountManager.addAccount(account, token)` seam.

**Decision.** v1 ships the **paste path as the real sign-in surface** — a `feature/signin` slice
hosted in the Auth shell (ADR-0013), not merely a dev affordance. On paste, the client **validates
the token first** with a one-off `GET /auth/me` carrying that token as an explicit bearer (a new
`SignInService` in `core/data` over an `AuthRemoteSource.fetchMe(token)` variant; the
`core/network` bearer plugin skips its active-Account provider header when `Authorization` is
already set). **Only on success** does it create the Account — `AccountId` derived from the backend
**User id**, label from the User's `display_name` (falling back to `username` when it is blank — the
contract field is non-nullable, so the real fallback case is an empty/whitespace name) — and store
the token via `addAccount`, after
which `activeAccount` flips and `RootComponent` swaps the Auth shell for Main. Browser-OAuth + PKCE
(the no-paste UX) stacks onto this same `SignInService` seam when #299 lands.

**Considered & rejected.** *Store the pasted token blindly* — a typo'd or expired PAT yields an
Account that 401s on its first real request; validating up front rejects it with an inline error and
yields a meaningful Account identity for free. *Build the speculative browser/PKCE flow now* — it
would churn against an unfinalized #299 wire contract and ship untestable, backend-blocked code.

**Consequences.** A *pasted* PAT carries no client-known server-side token id, so logout stays
**local-wipe-only** — server-side revoke (`DELETE /auth/tokens/{id}`, ADR-0009/0012) waits for the
browser-mint path, which returns the id at mint time. `AccountId = UserId` makes re-pasting the same
identity **idempotent** (an upsert, never a duplicate Account). This pass is **initial sign-in
only**; the Auth-shell *re-entry* for adding a second Account while one is active (ADR-0013) — and
the candidate-token-vs-active-Account bearer precedence it implies — is a follow-up.

**Update (2026-06-10, Deferno#299 landed).** The browser-mint path it anticipated has shipped — see
**ADR-0026**. The paste path is now demoted from "the real sign-in surface" to a **developer-only
affordance** (a debug-gated "Use a token instead" reveal), kept as a dev convenience and MFA escape
hatch; the system-browser OAuth flow is the primary surface. The "logout stays local-wipe-only"
consequence is lifted **for browser-minted accounts** (they carry a server-side token id and now
revoke via `DELETE /auth/tokens/{id}`); pasted tokens still carry no id, so they remain
local-wipe-only — which also keeps the shared dev PAT from being revoked.

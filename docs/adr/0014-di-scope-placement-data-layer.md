# DI scope placement: the per-Account data layer is AccountScope, not AppScope

**Context.** ADR-0008 **G2** lists "the data layer (repositories, SQLDelight, sync engine,
`AccountManager`, secure vault)" as process-global singletons in **AppScope**, shared across scenes,
with only presentation scene-scoped. But ADR-0002 mandates hard Account isolation through a
**per-Account encrypted database** (its own SQLCipher file + device-bound key), and the data-layer
code is per-Account *by construction*: `AndroidSqlDriverFactory` opens `deferno-<id>.db` under that
Account's key; `OfflineTaskRepository`/`OfflinePlanRepository` take a *fixed* local store; the outbox
table lives *inside* that per-Account DB. A single shared AppScope repository/DB instance cannot serve
two Accounts without either leaking across the isolation boundary or being rewritten into an
Active-Account-routing singleton. The **network** layer is the opposite case — `AccountBearerTokenProvider`
reads the [[Active Account]]'s PAT *fresh per request* (ADR-0012), so one `HttpClient` follows Account
switches with no rebuild. G2's flat "data layer in AppScope" therefore conflates two layers that
belong in different scopes.

**Decision.** Split the data layer across the existing App → Account → Scene scopes (ADR-0008):

- **AppScope (process-global, shared, one per process):** `DefernoEnvironment`, the `HttpClient` +
  `AccountBearerTokenProvider` + Ktor remote sources, `SecretVault`, `DatabaseKeyProvider`,
  `AccountManager`/`AccountContext`, the reauth coordinator, `AccountRegistry`, `AccountDataStore` —
  cross-Account infrastructure, or readers of the Active Account that need no rebuild on switch.
- **AccountScope (bound to the Active Account; torn down + rebuilt on switch):** the per-Account
  `SqlDriverFactory` → `DefernoDatabase`, the SqlDelight task/plan/outbox stores, the `Offline*Repository`s,
  the outbox writers, `CommandExecutor`, and the `OutboxProcessor`. The outbox is per-Account *for free* —
  it lives inside the per-Account DB (no `account_id` column needed).
- **SceneScope:** the Decompose component tree + ViewModels (unchanged from ADR-0008).

The Active-Account **lifecycle is owned by the per-scene `RootComponent`** (ADR-0013): its Auth/Main
shell selector carries the `AccountId` in the Main configuration (`Config.Main(accountId)`), so a fast
user switch (A→B) re-keys the Main child, which rebuilds the `AccountComponent` (→ `SceneComponent` →
repositories) for the new Account — reusing the same Decompose navigation mechanism that swaps
Auth↔Main. Switching needs **no re-authentication**: every Account's PAT already lives in the AppScope
`SecretVault` (ADR-0012).

This **refines ADR-0008 G2** — the cross-Account infrastructure it names (`AccountManager`, secure
vault — and we add the network client + `DatabaseKeyProvider`) stays AppScope, but **repositories,
SQLDelight, and the sync engine move to AccountScope**.

**Considered & rejected.** Keeping the whole data layer in AppScope as G2's prose reads — rejected
because it forces `OfflineTaskRepository`/`OfflinePlanRepository` to be rewritten as
Active-Account-routing singletons (selecting the right per-Account encrypted DB internally), which moves
the ADR-0002 isolation boundary *out* of the scope graph and into hand-written routing in every
repository: more code, and a weaker, easier-to-breach boundary.

**Consequences.** Account isolation is enforced **structurally** by the scope graph — a per-Account
graph holds exactly one Account's DB/repos and is disposed on switch, so Account B's graph can never
observe Account A's data. Fast user switching is cheap (presentation + per-Account data rebuild from the
local encrypted DB; the shared AppScope network/identity layer is untouched). The `Scopes.kt` /
`AccountComponent.kt` doc comments — which currently follow G2's wording — must be corrected to match.
Multi-window (ADR-0008, deferred) still composes: a second window builds a second `AccountComponent`
over a different Active Account sharing the one `AppComponent`.

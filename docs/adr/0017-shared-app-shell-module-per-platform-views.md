# The app shell is a shared KMP module; its Views stay per-platform

**Context.** ADR-0013 defines a two-state [[Shell]] (Auth ↔ Main) above a [[Destination]] graph, and
ADR-0003 splits each surface into **shared, Compose-free component state** + a **thin, per-platform
native [[View]]**. When the desktop shell first landed (#59) the DI graph didn't exist yet, so the
Compose-free shell components (`RootComponent`, `MainShellComponent`, `Destination`, `AccountSession`,
`NewComponent`, `AuthShellComponent`) were **hand-duplicated** from `app/androidApp` into
`app/desktopApp` over in-memory stub repositories + a boolean `AuthGate` — an explicit
throwaway-until-DI choice. #68 then landed the real kotlin-inject/anvil graph (ADR-0014), **including
the full JVM target** (`PlatformContext.jvm`, `JvmDatabaseBindings`, `DesktopSecretVault`, a generated
JVM `AppComponent`), but only Android was rewired to consume it. The desktop copy drifted three PRs
behind (2 Destinations vs 5, no overlay primitive, no account switching, no live theming), and the
duplication guarantees that drift recurs on every shell change.

**Decision.** Extract the Compose-free shell components into a new shared module **`app/shell`**
(`deferno.kmp.library`, Android + JVM + iOS), consumed by every app entry point. The shell **Views**
stay **per-platform**: Compose in `app/androidApp` (the adaptive `NavigationSuiteScaffold`) and
`app/desktopApp` (a rail/drawer + in-app menu bar), and **SwiftUI on iOS** — now live production code
(`app/iosApp/iosApp/`: `RootView.swift`, `MainShellView.swift`, and the Kotlin bridges), with the
native macOS SwiftUI surface following the same shared shell (ADR-0029). Desktop moves
**onto the real JVM DI graph** — `createAppComponent(PlatformContext(dir), Staging)` →
`AccountComponentSession(createAccountComponent(appComponent, account))` — exactly as `DefernoApplication`
+ `MainActivity` do, **retiring** `StubAuthGate`, the demo repositories, and `SampleData` from the
runtime path (the demo fakes survive only in test source sets). The shell now keys off
`AccountManager.activeAccount` (real account switching), not a boolean gate.

`app/shell` sits **above** `feature/*` (it composes four feature slices + `core/di`) and **below** the
app entry points — refining ADR-0004's `core/* → feature/* → app/*` layering so that `app/` holds both
the platform applications and the shared shell library they render. The shared `RootComponent` stays
host-agnostic via constructor lambdas (`onOpenOsAppSettings`, `onOpen…`): each host supplies its own
(desktop browses with `java.awt.Desktop`, omits the Android-only OS-app-settings affordance). (Sign-in
is no longer one of these lambdas — it moved to an injected `SignInService` per ADR-0023.)

**Consequences.** The drift is structural-impossible-by-construction: one shell, rendered three ways.
Desktop at runtime now requires a configured **dev-PAT** (surfaced from `local.properties` into a
Gradle-generated constant, mirroring Android's `BuildConfig`; #68/ADR-0012), **network access** to
staging, and an **OS keychain** (`DesktopSecretVault` over libsecret/Keychain/Credential Store) — a
headless host with no Secret Service throws `SecureStorageException`. The desktop per-Account database
is **file-backed SQLite with no SQLCipher** yet (the JVM bindings rely on OS disk protection;
SQLCipher-on-JVM is a tracked follow-up). The `NavSlot` Primary/Secondary distinction is a no-op on
desktop (the rail/drawer lists every Destination directly; "More" is compact-only).

**Rejected.**

- **Re-duplicate the updated shell into `app/desktopApp`** — fastest now, but reinstates the exact
  copy-drift this ADR exists to kill; the throwaway-until-DI condition that justified it in #59 is gone.
- **A shared Compose `app/shell/ui` module** (shell Views in `commonMain`) — `app/shell` must stay
  Compose-free to keep its iOS target (the Compose compiler is module-wide, ADR-0004 #27), and iOS
  renders the shell in **SwiftUI**, so a shared *Compose* shell-ui would only ever serve Android + JVM
  — the two that already have per-app hosts. The two nav surfaces diverge **by design** (ADR-0007's
  "not a stretched phone" non-goal), leaving only marginal form-sharing — not worth a module.
- **Keep desktop on demo data** (fake `AccountManager` + in-memory repositories) — the shared
  `RootComponent` is built over `AccountManager` + an `AccountSession`, so this means writing *new*
  throwaway `Settings`/`WorkingState`/`create` fakes to satisfy the contract: throwaway code to replace
  the throwaway code being deleted, while the fully-built JVM graph sits unused.

# Environment as a build variant: coexisting, isolated prod/staging installs for dogfooding

**Status.** Accepted. Phone-first (Android + iOS); desktop/macOS positioned but deferred.

**Context.** The server environment is **welded to build type**: `debug → Staging`, `release →
Production`, decided at process start from `BuildConfig.DEBUG` (Android `DefernoApplication.kt`) and
`Platform.isDebugBinary` (iOS/macOS `DefernoRoot.kt`), then threaded into AppScope via
`createAppComponent(platform, environment)`. `DefernoEnvironment` (`core/network`) is a closed enum —
`Production` (`app.defernowork.com`), `Staging` (`app2.defernowork.com`), `Local` (`localhost`, never
selected by any entrypoint). Both build types share one Android `applicationId`
(`com.circuitstitch.deferno`) and one iOS bundle id, so **a staging build and a prod build cannot
coexist on a device** — installing one replaces the other — and there is **no runtime environment
picker**.

The maintainer wants to genuinely **dogfood on Production** (personal iPhone as the daily driver),
while (a) never muddying the real [[Account]] with test data, (b) never letting Claude/automation
drive a real device against real data, and (c) still reproducing/debugging prod-only bugs. The status
quo blocks all three: Production is reachable **only** via a non-debuggable release build with **no
dev-PAT seeding**; the only debuggable/seedable builds (debug) reach **only** Staging and **cannot
coexist** with the prod install. So there is no way today to run a debuggable, dev-seeded build against
Production alongside the real dogfood app.

**Decision.**

- **Environment becomes a build-variant dimension, decoupled from build type.** Each variant *injects*
  its `DefernoEnvironment` into `createAppComponent(...)`; startup no longer derives it from
  `BuildConfig.DEBUG` / `Platform.isDebugBinary`. No DI-graph change — the AppScope already takes
  `environment` as a parameter.
  - **Android:** an `environment` **product-flavor dimension** (`prod`, `staging`) supplies the choice
    via a per-flavor `buildConfigField`; `DefernoApplication` reads that instead of `BuildConfig.DEBUG`.
  - **iOS:** a per-**Xcode-configuration** build setting (surfaced through `Info.plist`), read by the
    Swift entry point and passed into `DefernoRoot(environment = …)`; the Kotlin framework stops
    calling `Platform.isDebugBinary` to pick the env.
- **Distinct application/bundle IDs per variant, so installs coexist and the OS isolates them.**
  App-id suffix = flavor suffix (`prod` → none, `staging` → `.staging`) + build-type suffix (`debug` →
  `.debug`). `prodRelease` keeps the exact current id `com.circuitstitch.deferno` so existing installs
  upgrade cleanly. The three variants in daily use coexist:

  | Variant | Backend | App / Bundle ID | Account | Driver |
  |---|---|---|---|---|
  | `prodRelease` | Production | `com.circuitstitch.deferno` *(unchanged)* | **Personal** | Maintainer — dogfood daily driver |
  | `prodDebug` | Production | `…deferno.debug` | **Test (prod)** | Claude & maintainer — prod repro/driving |
  | `stagingDebug` | Staging | `…deferno.staging.debug` | **Test (staging)** | Dev work on staging |

  Because the roster, secret vault, and per-Account DB all key on **`AccountId` only**
  (`deferno_account_roster` prefs, per-Account `SecretVault`, `deferno-<id>.db`) and the OS already
  isolates by app id, each install is a **wholly separate account world** — no environment-namespacing
  of the stores is required.
- **Per-variant icons + names** so they are never confused: a color-shifted launcher icon / "DEBUG"
  wordmark plus an app-name suffix ("Deferno" · "Deferno β" · "Deferno Dev"). Android = per-flavor
  `src/<flavor>/res` icon + `app_name` string (the framework-read `app/androidApp/res/values*`);
  iOS = per-configuration `ASSETCATALOG_COMPILER_APPICON_NAME` + `CFBundleDisplayName`.
- **Account placement is structural, not disciplinary.** The Personal [[Account]] lives **only** in
  `prodRelease`; the Test account(s) live **only** in `prodDebug`/`stagingDebug`, auto-seeded from the
  gitignored `local.properties` dev-account mechanism (ADR-0012's paste path) extended with a **prod
  key** (`deferno.prod.accounts`) consumed by the `prod` flavor's debug build. Release variants seed
  nothing, as today. Claude/automation and physical-device debugging touch **only** Test installs; the
  personal [[Personal access token (PAT)]] never sits in a debuggable build, and no cross-app "switch to
  Personal" affordance exists to fumble.
- **Device posture:** Claude drives the **iOS Simulator** (Test account) for the bulk of work; the
  physical iPhone is used only for genuine device-only bugs, and then only the isolated Test install.
  No dedicated second device.

**Rollout (phased).**

0. **Prereq (manual/external):** mint a Production [[Personal access token (PAT)]] for a **disposable
   prod Test account** (web User Settings, ADR-0012). This is the one load-bearing assumption below.
1. **Shared core:** stop deriving the env from build type in both entry points; accept an injected
   `DefernoEnvironment` (Android from a flavor `buildConfigField`; iOS from an `Info.plist` value the
   Swift root reads and passes in). No DI change.
2. **Android flavors:** `flavorDimensions("environment")` + `productFlavors { prod; staging {
   applicationIdSuffix ".staging" } }` with the `debug` build type's `.debug` suffix; per-flavor
   `DEFERNO_ENV` field; move `DEV_ACCOUNTS`/token wiring per-flavor (`staging` ← `deferno.dev.accounts`
   / `deferno.staging.apiToken`; `prod` debug ← new `deferno.prod.accounts`; releases `""`); per-flavor
   icon + `app_name`. Verify `installProdRelease` / `installProdDebug` / `installStagingDebug` coexist.
3. **iOS configurations:** per-variant `PRODUCT_BUNDLE_IDENTIFIER` + a `DEFERNO_ENV` build setting into
   `Info.plist`; Swift reads it and passes into `DefernoRoot`; per-config app icon + `CFBundleDisplayName`;
   dev provisioning profiles per bundle id; seed the prod Test account via a gitignored dev key mirroring
   `local.properties`. Verify coexistence + distinct icons on device and simulator.
4. **Polish + docs:** finalize the color/"DEBUG" treatment; update CLAUDE.md "Common commands" with the
   variant install tasks and the new `local.properties` keys.

**Considered & rejected.**

- **Runtime environment picker (one install, flip prod⇄staging).** The roster, `SecretVault`, and
  per-Account DBs key on `AccountId` **with no environment namespace**, so a staging PAT and a prod PAT
  for the same account id would collide and the DBs would be shared — a picker is unsafe until every
  store is namespaced by environment (a real data-model change). Coexisting installs get OS-level
  isolation for free, and are the safer default.
- **Status quo (dogfood via the release build; keep debugging on staging).** Leaves Production forever
  non-debuggable and un-seeded, and debug/prod can't coexist, so prod-only repro and Claude-driven prod
  sessions are impossible without reinstall churn — precisely the barriers.
- **A dedicated second iPhone.** OS-isolated installs + the simulator already remove the data risk;
  a second device is hardware + upkeep (charging, iOS updates, provisioning) for marginal gain. Left
  available if physical-only automation ever warrants it.
- **A `.debug` build-type suffix only (no env flavor).** Buys debug/release coexistence but **not a
  debuggable *prod* build** — environment would still track build type, so the core need is unmet.

**Consequences.**

- New ids mean per-variant signing/provisioning: Android debug variants use the debug keystore (fine);
  iOS needs a **provisioning profile per bundle id**. **Browser-PKCE sign-in (ADR-0026) needs a
  redirect-URI registration per new bundle/app id** — but dev-seeding via pasted [[Personal access token
  (PAT)]] (ADR-0012) sidesteps it, so Test installs work with **no OAuth change**; browser sign-in on
  suffixed variants is a later add. If Android carries Firebase/`google-services.json`, each new
  `applicationId` must be registered (or the plugin relaxed) — verify at wiring time.
- The prod Test-account isolation **rests on being able to mint a prod PAT for a disposable Production
  account**. If prod registration is gated and throwaway prod accounts aren't realistic, the Test
  account falls back to Staging and `prodDebug` loses its isolated test identity — the single external
  prerequisite, called out so it fails loudly rather than silently.
- **Per-variant dev-seeding is wired asymmetrically at first.** Android sources each debug variant's
  seed from a distinct gitignored `local.properties` key (the `prod` flavor's debug build ←
  `deferno.prod.accounts`; `staging` ← `deferno.dev.accounts` / `deferno.staging.apiToken`), so the two
  never cross. iOS still has a single `DEV_STAGING_TOKEN` slot (the shared `Local.xcconfig` →
  `Secrets.xcconfig` chain feeds *both* debug configs), so to keep the Production-env `prodDebug` install
  from ever seeding a *staging* [[Personal access token (PAT)]] its config **pins `DEV_STAGING_TOKEN =
  ""`** (like Release) and seeds nothing. A per-config iOS prod-seed key (the twin of
  `deferno.prod.accounts`) is a follow-up that lands with the manual prod PAT above.
- Staging is **no longer the maintainer's daily driver** — it becomes the `stagingDebug` dev/validation
  target. `DefernoEnvironment.Local` stays defined but unwired.
- **Desktop/macOS** are positioned but **deferred**: the shared inject-the-environment change lets them
  adopt variant-based selection later (desktop currently hardcodes `Staging`; macOS uses
  `isDebugBinary`). No wire, DTO, or DI-graph change anywhere — `createAppComponent` already takes the
  environment. When they migrate, the shared seam is `DefernoEnvironment.fromName(name)` (`core/network`):
  the single home for the build-injected env-string → enum mapping the Android/iOS entry points already
  call, so they resolve their injected env through it rather than re-deriving a third/fourth copy.
- `CONTEXT.md` is unchanged: `Account` (hard isolation + fast switch), `App setting` (device-local), and
  `Personal access token (PAT)` already name every concept this relies on; "Personal" vs "Test" are just
  two labeled Accounts, not new vocabulary.

Cross-references ADR-0002 (Account hard isolation — the boundary this leans on), ADR-0012 (dev-PAT paste
seeding — extended per-flavor), ADR-0026 (browser PKCE — the redirect-URI consequence), ADR-0009
(device-bound secure storage), ADR-0005 (the envelope floor tracks the live staging version),
ADR-0003/0004 (native UI per platform; build config lives in `build-logic`/`ProjectConfig` + the
convention plugins, where the flavor/toolchain wiring belongs).

# Desktop release + self-update: Conveyor over jpackage-DIY, off public GitHub Releases

**Context.** We want a **desktop release pipeline and an in-app self-update** mechanism — the latter
checking the project's GitHub Releases and updating on user request — even though the app is far from
finished; the goal is the framework. Three facts shape the choice. (1) **Open-sourcing the client under
Apache-2.0 (ADR-0020) dissolves the original blocker:** a private repo would have forced an updater to
ship an extractable token to read Releases; a **public repo means public Releases, no embedded
credential.** (2) The **runtime is not a deciding factor** — the JetBrains Runtime (ADR-0019,
[[desktop-app-runtime-config]]) is preserved under either tool (today's `jpackage` already bundles it;
Conveyor `jlink`s whatever JDK input you give it and treats JBR as a first-class
`/stdlib/jdk/<v>/jetbrains.conf` input, needing the jmods-bearing **jbrsdk** that Foojay `JETBRAINS`
already resolves), and we are anyway open to revisiting Temurin. (3) On **Linux the convention is the
package manager / distro repo**, not in-app self-update — so in-app update is a **Windows/macOS**
concern, and Linux is served by an apt/yum repo or Flathub.

**Decision.**

- **Adopt Hydraulic Conveyor as the desktop packager + auto-updater**, over a build-your-own path
  (`jpackage` + a 3-OS GitHub Actions matrix + a hand-rolled delegated-installer updater). Conveyor is
  purpose-built for self-updating Compose Desktop apps, **free for OSI-licensed projects** (only
  obligation: an attribution link), and **cross-builds Windows + macOS + Linux from a single machine**
  — eliminating the per-OS CI matrix `jpackage` forces (it cannot cross-compile). Its built-in update
  engine (MSIX block-map deltas on Windows, Sparkle bsdiff on macOS) is **background, delta, and silent
  on restart** — strictly better than the "download the full installer and relaunch" model we had
  otherwise chosen. **The first Linux slice (#102) bundles the JDK Conveyor auto-imports from the
  module's Gradle toolchain — OpenJDK 17 / Temurin — not JBR.** Forcing JBR would mean either a fragile
  override of the auto-imported JDK or pulling the desktop compile toolchain off the shared Temurin;
  shipping the default is the lower-risk start (this ADR was already "open to revisiting Temurin"). The
  local dev `run` task still launches on JBR via `javaHome`. Bundling JBR in the Conveyor package
  (a one-line `/stdlib/jdk/17/jetbrains.conf` include + verification) is a deferred follow-up.
- **Releases + the update feed live on the public main repo's GitHub Releases**
  (`Circuit-Stitch/deferno-kmp`): `app.site.base-url = github.com/$repo/releases/latest/download`. The
  app ships **zero credentials**; cutting a Release *is* shipping an update.
- **The self-updater is desktop-only.** Android/iOS update through their stores; on Linux the package
  manager / a distro repo updates the app, so in-app self-update covers **Windows + macOS** only.
- **Single version source of truth in `ProjectConfig`** (matching the "build config lives in
  build-logic" rule). One `APP_VERSION` feeds Android `versionName` (+ a derived `versionCode`), the
  desktop `packageVersion` / `project.version` Conveyor reads (mapped to `app.version`), and the
  `$APP_VERSION` release tag — replacing today's split (`packageVersion "1.0.0"` vs
  `versionName "0.1.0"`/`versionCode 1`).
- **A manual plain-semver tag (e.g. `0.1.0`, no `v` prefix) triggers the release.** Conveyor owns
  release creation and names the GitHub Release/tag after `app.version` verbatim, and a `v` prefix is
  illegal in a Conveyor version — so the trigger tag is the **bare** `$APP_VERSION`, which then equals
  Conveyor's release tag exactly (one tag, no orphan; a `v$APP_VERSION` tag would leave a second,
  mismatched tag). Pushing the tag runs CI on one Linux runner: verify the tag matches `APP_VERSION` →
  the official Conveyor action runs `conveyor make copied-site` → publish to a GitHub Release.
  (Automated version/changelog tooling such as release-please is a deferred, additive option.)
- **Signing is phased, Linux-first.** Conveyor self-serves GPG-signed `deb`/apt from a generated key
  (zero external certs) for the dogfood now; **macOS notarization + Windows Authenticode** slot in
  later as **secrets-only** config — and Conveyor can perform both **from Linux** (no Mac needed).

**Forward path.** Tracer-bullet first slice: **version SoT + a tag-triggered Conveyor build publishing
a Linux package to a GitHub Release.** Follow-ups: the Windows/macOS targets + their in-app update,
macOS/Windows signing (secrets only), and the Linux distro story (a yum/dnf or Flathub presence —
*not* produced by Conveyor; see below). The whole thing is testable end-to-end on Linux before any
paid certs or other OSes are involved.

**Consequences.**

- **Process-level lock-in:** `conveyor.conf` and the update mechanism become Conveyor-specific. The
  *output* packages are standard (`.msi`/`.dmg`/`.deb`/MSIX/apt), so binaries aren't locked, but
  leaving Conveyor means rebuilding packaging + update. The free tier is contingent on staying OSS
  (commercial pricing if the project ever closes), and the license key is bound to the update-site URL.
- A separate **`conveyor` CLI** must be installed in CI (not a pure Gradle dependency), and the
  **attribution link** is a hard condition of the free license.
- **Fedora gap (named, accepted):** Conveyor's Linux output is an **apt repo + tarball** — it does
  **not** generate a yum/dnf repo or an RPM. Fedora (the maintainer's own box) is served by the tarball
  for dogfooding; native Fedora distribution is a separate later effort (COPR/Flathub, or a
  supplemental `jpackage` RPM). Until then mac/Windows dogfood builds are self-signed and show OS
  warnings.
- **Runtime divergence while JBR-bundling is deferred (#102):** the Conveyor package ships OpenJDK 17 /
  Temurin (the auto-imported toolchain JDK) while local `run` uses JBR, so packaged Linux builds may
  differ slightly in window-manager decorations / theme integration. When JBR-bundling is taken up, it
  must be supplied as the **jbrsdk** (jmods-bearing) flavor for Conveyor's `jlink` step — satisfiable
  via the project's Foojay `JvmVendorSpec.JETBRAINS` resolution and a `/stdlib/jdk/17/jetbrains.conf`
  include, with a per-target build smoke-test.

**Considered & rejected.**

- **Build-your-own (`jpackage` + 3-OS GitHub Actions matrix + hand-rolled delegated-installer
  updater).** Keeps the existing packaging untouched and has zero lock-in, but: `jpackage` can't
  cross-compile, so a **per-OS runner matrix is mandatory**; the updater is **bespoke code we'd own and
  maintain** with **no viable off-the-shelf library** (update4j is archived, getdown is unmaintained,
  and both fight a JBR-bundled `jpackage` image); and updates are full re-downloads with an elevation
  prompt, no delta/background. Conveyor deletes all of that for free. Retained as the fallback if
  Conveyor's lock-in or OSS-contingency ever bites.
- **The originally-chosen "delegated native-installer" update model** (download the new
  `.msi`/`.dmg`/`.deb`, launch it, relaunch). Superseded: Conveyor delivers a *superior* version of the
  same intent (OS-native packaging **with** background/delta self-update) on Windows/macOS, and the
  Linux half of that model is replaced by the distro-repo convention.
- **Private-repo updater workarounds — embedding a read token, a separate public releases repo, or a
  self-hosted static manifest/CDN.** All existed only to dodge the private-repo secret problem;
  **open-sourcing (ADR-0020) moots every one of them.**
- **AGPL "to protect the SaaS"** — out of scope here, rejected in ADR-0020 (its network clause misfires
  for a locally-run client).

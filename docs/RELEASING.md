# Releasing the desktop app

The desktop release pipeline is **Hydraulic Conveyor** publishing to **public GitHub Releases**
(ADR-0021). One `conveyor make copied-site` on a **single Linux runner** cross-builds **Linux + Windows
+ macOS** (Intel + Apple Silicon) — no per-OS runner matrix, no Mac — each bundling **Eclipse Temurin /
OpenJDK 17** (#103; the desktop standardizes on Temurin — no JBR). Windows + macOS are **self-signed for
now** (#103): they install with an OS warning
(SmartScreen / Gatekeeper) until production signing — Apple notarization + Windows Authenticode — lands
in **#104**. Linux is distributed via the package manager (apt repo + tarball now; Fedora/Flathub in
**#105**), not in-app self-update.

The spine is: **`ProjectConfig.APP_VERSION` → git tag → CI → Conveyor → public GitHub Release.**

## Version source of truth

One value, `ProjectConfig.APP_VERSION` (`build-logic/.../gradle/ProjectConfig.kt`), drives everything
(#101):

| Artifact | Where it derives | Example (`APP_VERSION = "0.1.0"`) |
| --- | --- | --- |
| Android `versionName` | `deferno.android.application` convention | `0.1.0` |
| Android `versionCode` | `ProjectConfig.ANDROID_VERSION_CODE` (`major*1_000_000 + minor*1_000 + patch`) | `1000` |
| Desktop `project.version` | `deferno.jvm.application` convention | `0.1.0` |
| Desktop `packageVersion` (jpackage) | `project.version` | `0.1.0` |
| Conveyor `app.version` | Gradle `project.version` via `printConveyorConfig` | `0.1.0` |
| Release tag | the bare version you push | `0.1.0` |

To bump the version, change `APP_VERSION` in `ProjectConfig.kt` — nothing else.

## Tag scheme: plain semver, NO `v` prefix

Conveyor **owns release creation** and names the GitHub Release/tag after `app.version` verbatim
(e.g. `0.1.0`); a `v` prefix is illegal in a Conveyor version and there is no key to rename the
release tag. So the trigger tag is the **bare** version — pushing `0.1.0` makes the trigger tag and
Conveyor's release tag identical (one tag, no orphan). The release workflow triggers on
`tags: ["[0-9]+.[0-9]+.[0-9]+"]` and **fails fast** if the pushed tag ≠ `APP_VERSION`.

## One-time setup (maintainer)

Conveyor requires a **root signing key** even for self-signed Linux packages. It is the only secret
besides the auto-provided `GITHUB_TOKEN`.

1. Install the Conveyor CLI: <https://conveyor.hydraulic.dev/download/>.
2. Generate the root key **once**:
   ```sh
   conveyor keys generate
   ```
   This writes `app.signing-key = "<24 words>/<timestamp>"` into your per-user
   `~/.config/Hydraulic/Conveyor/defaults.conf` (path is OS-specific). **Keep it secret and back it
   up** — it is not in git, and losing it means users can't auto-update from a key they already trust.
3. Add it as a repository secret named **`SIGNING_KEY`** (the full `<24 words>/<timestamp>` string):
   ```sh
   gh secret set SIGNING_KEY --repo Circuit-Stitch/deferno-kmp
   # paste the value from defaults.conf when prompted
   ```
   The release workflow passes it to Conveyor as `app.signing-key`. Its **private half stays in the
   secret and never ships in the artifact**; only the derived public/apt key is embedded for
   verification — satisfying "no credential embedded in the built artifact".

No separate "OSS license key" exists: Conveyor's free open-source tier is unlocked purely by
`app.vcs-url` pointing at the public repo (set in `conveyor.conf`). The "Packaged with Conveyor"
download-page badge is a required attribution for the free tier — leave it on.

## Cut a release

```sh
# 1. Bump the single source of truth and commit.
#    Edit ProjectConfig.APP_VERSION, e.g. "0.1.0" -> "0.1.1"
git commit -am "release: 0.1.1"

# 2. Tag with the BARE version (no `v`) and push.
git tag 0.1.1
git push origin main 0.1.1
```

Pushing the tag runs `.github/workflows/release.yml` on one Linux runner: it verifies the tag matches
`APP_VERSION`, then the official Conveyor action runs `conveyor make copied-site`, cross-building the
Linux (`.deb` + tarball + apt repo), Windows (MSIX + `.appinstaller` + installer EXE), and macOS
(per-arch app ZIPs + Sparkle appcasts) packages and publishing them + the Conveyor update metadata to a
public GitHub Release named `0.1.1`. The artifacts are downloadable without authentication.

## In-app self-update (Windows + macOS)

Self-update is **Windows + macOS only** (ADR-0021): the OS update engines Conveyor wires up — Windows
MSIX/AppInstaller block-map deltas, macOS Sparkle deltas — check the GitHub Release in the background
and apply on restart. **Cutting a new Release *is* shipping an update**: installing version N then
publishing N+1 makes installed Win/Mac apps update themselves to N+1.

The desktop app also exposes a manual **Help → Check for updates** menu item plus a menu-bar **"Update
available" / "Updating…"** badge (`app/desktopApp/.../desktop/update/`), driven by Conveyor's
`SoftwareUpdateController` (the `dev.hydraulic.conveyor:conveyor-control` library). It reads the running
version, fetches the published version, and — when newer — triggers the OS-native update + restart. When
the app is **not** Conveyor-packaged (a `./gradlew :app:desktopApp:run` dev session) the controller is
absent and the menu falls back to "View all releases…"; on **Linux** it points at the package manager.

> **Self-signed caveat (until #104):** a self-signed macOS app is not Gatekeeper-trusted, so it can't be
> opened by double-click — Conveyor's download page ships a one-line `curl | bash` install script that
> removes the quarantine flag. Windows shows a SmartScreen prompt. Both go away once #104 wires the real
> certs. Self-update itself works on the self-signed builds.

## Test the package locally (optional)

With the Conveyor CLI installed and the root key generated (steps above), from the repo root:

```sh
conveyor make site
```

This cross-builds every target (Linux `.deb` + tarball, Windows MSIX/EXE, macOS ZIPs) + the static
download page under `output/` **without** uploading (no `OAUTH_TOKEN` needed). Install/run the Linux
`.deb` (or extract the tarball) and confirm it launches and works. `conveyor make copied-site`
additionally uploads, and is what CI runs.

> **Bundled runtime: Eclipse Temurin / OpenJDK 17 on every machine** (#103) — the JDK the Conveyor
> Gradle plugin auto-imports from the desktop module's toolchain (`/stdlib/jdk/17/openjdk.conf`). The
> desktop standardizes on Temurin everywhere: dev `./gradlew :app:desktopApp:run`, jpackage, and the
> Conveyor package all use it (no JetBrains Runtime — the JBR rendering advantage was a misdiagnosis,
> ADR-0021). Conveyor `jlink`s it; a full Temurin JDK ships jmods, so no extra include or override is
> needed.

> The Android SDK must be available (`ANDROID_HOME`) when Conveyor runs, because its
> `printConveyorConfig` include configures the whole Gradle build (which includes the Android native
> speech module). CI's `ubuntu-latest` has it preinstalled.

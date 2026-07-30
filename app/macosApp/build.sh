#!/bin/sh
# Builds (and optionally launches) the native macOS app (ADR-0029, #188).
#
# XcodeGen generates the .xcodeproj from project.yml and CocoaPods (SQLCipher) layers a .xcworkspace on
# top — and regenerating the project drops the pod integration, so `pod install` must follow every
# `xcodegen generate`. This script runs that two-step setup, then builds the workspace. The shared Kotlin
# framework is built by the project's own pre-build phase (`embedAndSignAppleFrameworkForXcode`); to
# iterate on the Kotlin side alone, run `./gradlew :app:macosApp:linkDebugFrameworkMacosArm64` instead.
#
#   ./build.sh           # generate + pod install + build
#   ./build.sh --open    # …then launch the built .app
#   ./build.sh --test    # …run the macosAppTests suite instead of a plain build (QUIT a running Deferno
#                        #  first — the app hosts the tests and refuses a second instance)
#
# --open and --test are mutually exclusive; either COMBINES with --config.
#
#   ./build.sh --config <Debug|ProdDebug|Release>    # which environment variant to build (ADR-0047)
#
# The three coexisting variants (project.yml `configs`, ADR-0047 / #368 G22) — separate installs, separate
# Keychain items, separate per-Account DBs:
#
#   Debug (default)  Staging     com.circuitstitch.deferno.macos.staging.debug   "Deferno Dev"
#   ProdDebug        Production  com.circuitstitch.deferno.macos.debug           "Deferno β"
#   Release          Production  com.circuitstitch.deferno.macos                 "Deferno"
#
#   ./build.sh --config ProdDebug --open    # build + launch the prod-repro install
#
# Requires xcodegen + cocoapods (`brew install xcodegen cocoapods`).
set -eu

cd "$(dirname "$0")"

WORKSPACE=macosApp.xcworkspace
SCHEME=macosApp
DESTINATION='platform=macOS,arch=arm64'

# Parse EVERY flag ONCE, up front — a typo must fail BEFORE the minutes of xcodegen + pod install below.
# `--test` swaps the plain build for the scheme's Test action: it builds the app AND the macosAppTests
# bundle, then runs the suite inside the app host. Same action .github/workflows/macos.yml gates on, so a
# green `./build.sh --test` locally is the same signal CI reports.
#
# The catch-all arm is what keeps the old failure modes closed: before it, two independent
# `[ "${1:-}" = … ]` tests failed silently in BOTH directions — `--test --open` dropped the second flag,
# and a typo (`./build.sh --tets`) matched neither and quietly did a plain build. The loop replaces the
# earlier one-flag-only `case` now that --config must COMBINE with --open/--test; --open and --test stay
# mutually exclusive, and an unknown flag or an unknown configuration name still exits 2 up here.
ACTION=build
OPEN=no
CONFIGURATION=Debug
while [ "$#" -gt 0 ]; do
  case "$1" in
    --open)
      if [ "$ACTION" = test ]; then
        echo "--open and --test are mutually exclusive" >&2
        exit 2
      fi
      OPEN=yes
      ;;
    --test)
      if [ "$OPEN" = yes ]; then
        echo "--open and --test are mutually exclusive" >&2
        exit 2
      fi
      ACTION=test
      ;;
    # Both spellings, because both are muscle memory. The arm shifts past the flag so the loop's own
    # trailing `shift` consumes the VALUE; `${1:-}` (set -u is on) catches a trailing bare `--config`.
    --config)
      shift
      CONFIGURATION="${1:-}"
      if [ -z "$CONFIGURATION" ]; then
        echo "--config needs a value: Debug, ProdDebug or Release" >&2
        exit 2
      fi
      ;;
    --config=*) CONFIGURATION="${1#--config=}" ;;
    *) echo "unknown flag: $1 (expected --open, --test or --config <Debug|ProdDebug|Release>)" >&2; exit 2 ;;
  esac
  shift
done

# Validate against the three configurations project.yml declares. Worth an explicit check: xcodebuild
# accepts an unknown -configuration and fails deep inside the build with a far less obvious message, and
# by then xcodegen + pod install have already run.
case "$CONFIGURATION" in
  Debug|ProdDebug|Release) ;;
  *) echo "unknown configuration: $CONFIGURATION (expected Debug, ProdDebug or Release)" >&2; exit 2 ;;
esac

# Machine-local signing (gitignored Local.xcconfig) is wired as the PROJECT-level base config in
# project.yml (`configFiles`) — the slot CocoaPods leaves alone — so its identity reaches Xcode-GUI builds
# too, not just this script. A stable signature keeps the login-Keychain ACL on the bearer token across
# rebuilds and stops the re-prompt (see Local.xcconfig). XcodeGen errors if the referenced file is
# missing, so seed an ad-hoc default ("-") when absent: a fresh clone / CI then generates + builds with no
# Dev account, exactly as before. An existing Local.xcconfig is never touched.
#
# SECOND SEEDING SITE: .github/workflows/macos.yml seeds its own (empty) Local.xcconfig before
# `xcodegen generate` — CI never runs this script. The two are equivalent today only because every value
# below is either overridden on CI's xcodebuild command line (the ad-hoc signing triple) or deliberately
# absent there (DEV_STAGING_TOKEN / DEV_ACCOUNTS — no PAT on a runner; an undefined build setting expands
# to empty in the Info.plist, so nothing is seeded). If a NEW setting is added here that CI does not
# override, update that step too or CI builds with a different config than every dev machine.
if [ ! -f Local.xcconfig ]; then
  echo "==> seeding ad-hoc Local.xcconfig (no Dev account; edit it to sign with a stable identity)"
  printf '// Auto-seeded ad-hoc signing. Edit to a stable identity so the Keychain ACL survives rebuilds:\n//   security find-identity -v -p codesigning   # lists yours\n// e.g. CODE_SIGN_IDENTITY = Apple Development: You (XXXXXXXXXX)\nCODE_SIGN_STYLE = Manual\nCODE_SIGN_IDENTITY = -\nPROVISIONING_PROFILE_SPECIFIER =\n// Dev-only staging PAT (#282): empty default; create a git-ignored Secrets.xcconfig with DEV_STAGING_TOKEN = <PAT> to skip sign-in.\n// Only the Debug (Staging) variant reads it — ProdDebug + Release pin it empty (ADR-0047).\nDEV_STAGING_TOKEN =\n// Dev-only prod Test account for the ProdDebug variant (ADR-0047): empty default; put\n// DEV_ACCOUNTS = <id:label:token;…> in that same Secrets.xcconfig. Debug + Release pin it empty.\nDEV_ACCOUNTS =\n#include? "Secrets.xcconfig"\n' > Local.xcconfig
fi

echo "==> xcodegen generate"
xcodegen generate

echo "==> pod install"
pod install

# QUIT a running Deferno first: the tests are hosted by the real app, and Info.plist sets
# LSMultipleInstancesProhibited, so LaunchServices refuses the test host while another instance is up —
# surfacing as "Could not launch macosAppTests", which reads like a build/signing problem but isn't.
# (A CI runner never has a second instance, so this is a local-dev papercut only.) Say so out loud rather
# than leaving the hazard in a source comment nobody reads mid-failure: the executable is `Deferno`, not
# the target name (PRODUCT_NAME, #189). Warn only — the run may still be worth attempting, and `pgrep`
# returning 1 (no match) must not trip `set -e`, hence the `if`.
if [ "$ACTION" = test ] && pgrep -x Deferno >/dev/null 2>&1; then
  echo "==> WARNING: Deferno is already running — quit it before the tests launch their host." >&2
  echo "    LaunchServices refuses a second instance (LSMultipleInstancesProhibited), and the failure" >&2
  echo "    reads as \"Could not launch macosAppTests\" — a launch problem, not a build/signing one." >&2
fi

echo "==> xcodebuild ($SCHEME, $CONFIGURATION, $ACTION)"
xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" -destination "$DESTINATION" "$ACTION"

if [ "$OPEN" = yes ]; then
  echo "==> open"
  # -configuration must be repeated here: BUILT_PRODUCTS_DIR is per-configuration, so without it this
  # would resolve the scheme's default (Debug) path and `--config ProdDebug --open` would launch the
  # PREVIOUSLY built staging app — silently, and looking exactly like the build had done nothing.
  APP=$(xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -configuration "$CONFIGURATION" -showBuildSettings \
    | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{d=$2} / FULL_PRODUCT_NAME /{n=$2} END{print d"/"n}')
  open "$APP"
fi

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
# One flag at a time; --open and --test are mutually exclusive.
#
# Requires xcodegen + cocoapods (`brew install xcodegen cocoapods`).
set -eu

cd "$(dirname "$0")"

WORKSPACE=macosApp.xcworkspace
SCHEME=macosApp
DESTINATION='platform=macOS,arch=arm64'

# Parse the flag ONCE, up front — a typo must fail BEFORE the minutes of xcodegen + pod install below.
# `--test` swaps the plain build for the scheme's Test action: it builds the app AND the macosAppTests
# bundle, then runs the suite inside the app host. Same action .github/workflows/macos.yml gates on, so a
# green `./build.sh --test` locally is the same signal CI reports.
#
# The `case` (with its catch-all arm) replaces two independent `[ "${1:-}" = … ]` tests that failed
# silently in BOTH directions: `--test --open` dropped the second flag, and a typo (`./build.sh --tets`)
# matched neither and quietly did a plain build. --open/--test stay mutually exclusive, now explicitly.
if [ "$#" -gt 1 ]; then
  echo "too many arguments: pass at most one flag (--open and --test are mutually exclusive)" >&2
  exit 2
fi
ACTION=build
OPEN=no
case "${1:-}" in
  '') ;;
  --open) OPEN=yes ;;
  --test) ACTION=test ;;
  *) echo "unknown flag: $1 (expected --open or --test)" >&2; exit 2 ;;
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
# absent there (DEV_STAGING_TOKEN — no PAT on a runner). If a NEW setting is added here that CI does not
# override, update that step too or CI builds with a different config than every dev machine.
if [ ! -f Local.xcconfig ]; then
  echo "==> seeding ad-hoc Local.xcconfig (no Dev account; edit it to sign with a stable identity)"
  printf '// Auto-seeded ad-hoc signing. Edit to a stable identity so the Keychain ACL survives rebuilds:\n//   security find-identity -v -p codesigning   # lists yours\n// e.g. CODE_SIGN_IDENTITY = Apple Development: You (XXXXXXXXXX)\nCODE_SIGN_STYLE = Manual\nCODE_SIGN_IDENTITY = -\nPROVISIONING_PROFILE_SPECIFIER =\n// Dev-only staging PAT (#282): empty default; create a git-ignored Secrets.xcconfig with DEV_STAGING_TOKEN = <PAT> to skip sign-in.\nDEV_STAGING_TOKEN =\n#include? "Secrets.xcconfig"\n' > Local.xcconfig
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

echo "==> xcodebuild ($SCHEME, Debug, $ACTION)"
xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
  -configuration Debug -destination "$DESTINATION" "$ACTION"

if [ "$OPEN" = yes ]; then
  echo "==> open"
  APP=$(xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -showBuildSettings \
    | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{d=$2} / FULL_PRODUCT_NAME /{n=$2} END{print d"/"n}')
  open "$APP"
fi

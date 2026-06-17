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
#
# Requires xcodegen + cocoapods (`brew install xcodegen cocoapods`).
set -eu

cd "$(dirname "$0")"

WORKSPACE=macosApp.xcworkspace
SCHEME=macosApp
DESTINATION='platform=macOS,arch=arm64'

# Machine-local signing (gitignored Local.xcconfig) is wired as the PROJECT-level base config in
# project.yml (`configFiles`) — the slot CocoaPods leaves alone — so its identity reaches Xcode-GUI builds
# too, not just this script. A stable signature keeps the login-Keychain ACL on the bearer token across
# rebuilds and stops the re-prompt (see Local.xcconfig). XcodeGen errors if the referenced file is
# missing, so seed an ad-hoc default ("-") when absent: a fresh clone / CI then generates + builds with no
# Dev account, exactly as before. An existing Local.xcconfig is never touched.
if [ ! -f Local.xcconfig ]; then
  echo "==> seeding ad-hoc Local.xcconfig (no Dev account; edit it to sign with a stable identity)"
  printf '// Auto-seeded ad-hoc signing. Edit to a stable identity so the Keychain ACL survives rebuilds:\n//   security find-identity -v -p codesigning   # lists yours\n// e.g. CODE_SIGN_IDENTITY = Apple Development: You (XXXXXXXXXX)\nCODE_SIGN_STYLE = Manual\nCODE_SIGN_IDENTITY = -\nPROVISIONING_PROFILE_SPECIFIER =\n' > Local.xcconfig
fi

echo "==> xcodegen generate"
xcodegen generate

echo "==> pod install"
pod install

echo "==> xcodebuild ($SCHEME, Debug)"
xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
  -configuration Debug -destination "$DESTINATION" build

if [ "${1:-}" = "--open" ]; then
  echo "==> open"
  APP=$(xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" -showBuildSettings \
    | awk -F' = ' '/ BUILT_PRODUCTS_DIR /{d=$2} / FULL_PRODUCT_NAME /{n=$2} END{print d"/"n}')
  open "$APP"
fi

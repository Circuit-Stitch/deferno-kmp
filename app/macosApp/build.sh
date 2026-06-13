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

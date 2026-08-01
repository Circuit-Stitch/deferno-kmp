#!/bin/bash
# The ONE xcodebuild recipe .github/workflows/macos.yml runs, invoked once per configuration it gates.
#
#   ./ci-xcodebuild.sh <scheme> <configuration> <action>
#
# ⚠️ CI ONLY. NEVER RUN THIS ON A DEV MAC. ⚠️
# It passes the ad-hoc signing triple below, and an ad-hoc signature changes on EVERY rebuild — so it
# never matches the login-Keychain ACL on the bearer-token item (service com.circuitstitch.deferno.bearer)
# and the app re-prompts `"Deferno" wants to use your keychain` on every single launch, with "Always Allow"
# never sticking. That is the exact failure app/macosApp/Local.xcconfig exists to prevent, and a
# command-line setting outranks the project base config, so running this locally overrides it. It has
# already happened once, by copying the recipe out of the workflow onto a dev machine — which is half the
# reason the recipe now lives in a file with a name that says CI on it.
# Locally use `./build.sh` / `./build.sh --test` / `./build.sh --config ProdDebug`, which pass NO signing
# flags and therefore inherit the stable identity from Local.xcconfig. To repair a Mac that got ad-hoc
# signed:  codesign --force --sign "Apple Development: <you> (TEAMID)" <path>/Deferno.app
#
# Why this is a script and not two copy-pasted `run:` blocks: the flags below are load-bearing and carry a
# standing sync obligation with build.sh's Local.xcconfig seeding ("if CI doesn't override a new setting,
# CI builds a different config than every dev machine"). One home for them means that obligation has one
# address, not one per gated configuration.
#
# Deliberately NOT a job-level matrix: both invocations share `-derivedDataPath build/dd`, so the second
# reuses the Kotlin framework + pod objects the first produced. Separate jobs would re-run xcodegen, pod
# install and Gradle from cold for each configuration.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <scheme> <configuration> <action>" >&2
  exit 2
fi
SCHEME=$1
CONFIGURATION=$2
ACTION=$3

cd "$(dirname "$0")"

# `set -o pipefail` (above) is the whole gate on this pipe. GitHub's default `run:` shell is `bash -e {0}`
# with NO pipefail, so piping xcodebuild into a formatter would hand the step xcbeautify's exit status and
# a failed build would report green — exactly what ios.yml did until PR #367. xcbeautify is preinstalled on
# the runner image; fall back to cat if a future image drops it.
if command -v xcbeautify >/dev/null 2>&1; then FMT=(xcbeautify --renderer github-actions); else FMT=(cat); fi

# SWIFT_TREAT_WARNINGS_AS_ERRORS is the ratchet ios.yml already carries: passed on the command line rather
# than set in project.yml, so a local Xcode build still only warns, and a future image bumping Xcode under
# us breaks CI loudly instead of silently reaccumulating warnings.
echo "==> xcodebuild ($SCHEME, $CONFIGURATION, $ACTION)"
xcodebuild \
  -workspace macosApp.xcworkspace \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination "platform=macOS,arch=arm64" \
  -derivedDataPath build/dd \
  CODE_SIGN_IDENTITY="-" \
  CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM="" \
  PROVISIONING_PROFILE_SPECIFIER="" \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES \
  "$ACTION" | "${FMT[@]}"

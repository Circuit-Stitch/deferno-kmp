#!/usr/bin/env bash
#
# Wrap the built helper binary into dist/Deferno.app — the .app bundle UNUserNotificationCenter
# requires (#123): a bare binary can't post notifications at all (see README), and the bundle's
# identity is what Notification Center shows, so the wrap carries the PRODUCT name ("Deferno",
# matching Info.plist's CFBundleName — LaunchServices ignores the plist names when the on-disk
# name differs) and the product flame icon. Conveyor packaging (#122) supersedes this for
# distribution; this is the dev/local wrap.
#
# Usage:
#   scripts/build.sh && scripts/wrap-app.sh
#   SIDECAR_SIGN_IDENTITY="-" scripts/wrap-app.sh   # ad-hoc sign (no Developer ID / locked keychain)
set -euo pipefail

cd "$(dirname "$0")/.."
PKG_DIR="$PWD"
DIST_DIR="$PKG_DIR/dist"
BIN="$DIST_DIR/deferno-sidecar"
APP="$DIST_DIR/Deferno.app"
ICON_SOURCE="$PKG_DIR/../../app/androidApp/src/main/ic_launcher-playstore.png"
ENTITLEMENTS="$PKG_DIR/Resources/deferno-sidecar.entitlements"

SIGN_IDENTITY="${SIDECAR_SIGN_IDENTITY:-Developer ID Application: Kyle Falconer (GDV76FJJZ5)}"

[ -x "$BIN" ] || { echo "error: $BIN not found — run scripts/build.sh first" >&2; exit 1; }

echo "==> Rendering Deferno.icns from the launcher icon"
ICONSET="$DIST_DIR/Deferno.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
swift "$PKG_DIR/scripts/render-icon.swift" "$ICON_SOURCE" "$ICONSET"
iconutil -c icns "$ICONSET" -o "$DIST_DIR/Deferno.icns"
rm -rf "$ICONSET"

echo "==> Assembling $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$PKG_DIR/Resources/Info.plist" "$APP/Contents/Info.plist"
cp "$BIN" "$APP/Contents/MacOS/deferno-sidecar"
cp "$DIST_DIR/Deferno.icns" "$APP/Contents/Resources/Deferno.icns"

echo "==> Codesign (hardened runtime; identity: $SIGN_IDENTITY)"
codesign --force --options runtime --timestamp=none \
  --entitlements "$ENTITLEMENTS" \
  --sign "$SIGN_IDENTITY" \
  "$APP"
codesign --verify --strict --verbose=2 "$APP"

# Tell LaunchServices about the (re)built bundle so Notification Center picks the name + icon up
# without waiting for a background rescan.
LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
[ -x "$LSREGISTER" ] && "$LSREGISTER" -f "$APP" || true

echo "==> OK: $APP"

#!/usr/bin/env bash
#
# Build + (Developer ID) sign the Deferno macOS Sidecar helper (ADR-0024, #121).
#
# Produces a release `deferno-sidecar` Mach-O at dist/deferno-sidecar with the Info.plist embedded
# (TCC usage strings) and signed under the hardened runtime so the embedded usage strings become
# visible to TCC and — with a stable Developer ID signature — the mic/Speech grants persist across
# rebuilds. Notarization + Conveyor packaging is the separate #122.
#
# Usage:
#   scripts/build.sh                      # release build, universal (x86_64+arm64), Developer ID sign
#   SIDECAR_ARCHS="x86_64" scripts/build.sh   # single-arch (faster; what you can verify on an Intel Mac)
#   SIDECAR_SIGN_IDENTITY="-" scripts/build.sh   # ad-hoc sign (CI without the cert)
#   SIDECAR_TIMESTAMP=1 scripts/build.sh  # secure timestamp (notarization-ready; needs network)
#
# Env knobs (all optional):
#   SIDECAR_SIGN_IDENTITY  codesign identity (default: the Developer ID Application below; "-" = ad-hoc)
#   SIDECAR_ARCHS          space-separated arch list (default: "x86_64 arm64" → universal)
#   SIDECAR_TIMESTAMP      "1" to use a secure timestamp (default: none — dev/local)
set -euo pipefail

cd "$(dirname "$0")/.."
PKG_DIR="$PWD"
DIST_DIR="$PKG_DIR/dist"
BIN_NAME="deferno-sidecar"
ENTITLEMENTS="$PKG_DIR/Resources/deferno-sidecar.entitlements"

SIGN_IDENTITY="${SIDECAR_SIGN_IDENTITY:-Developer ID Application: Kyle Falconer (GDV76FJJZ5)}"
ARCHS="${SIDECAR_ARCHS:-x86_64 arm64}"

echo "==> Building $BIN_NAME (release; archs: $ARCHS)"
ARCH_FLAGS=()
for arch in $ARCHS; do ARCH_FLAGS+=("--arch" "$arch"); done
swift build -c release "${ARCH_FLAGS[@]}"

BUILT="$(swift build -c release "${ARCH_FLAGS[@]}" --show-bin-path)/$BIN_NAME"
mkdir -p "$DIST_DIR"
cp -f "$BUILT" "$DIST_DIR/$BIN_NAME"   # sign a copy at a stable path — swift build drops the signature
BIN="$DIST_DIR/$BIN_NAME"

echo "==> Verifying embedded Info.plist (__TEXT,__info_plist)"
otool -s __TEXT __info_plist "$BIN" >/dev/null
echo "    embedded Info.plist present"

echo "==> Codesign (hardened runtime; identity: $SIGN_IDENTITY)"
TS_FLAG="--timestamp=none"
[ "${SIDECAR_TIMESTAMP:-0}" = "1" ] && TS_FLAG="--timestamp"
codesign --force --options runtime "$TS_FLAG" \
  --entitlements "$ENTITLEMENTS" \
  --sign "$SIGN_IDENTITY" \
  "$BIN"

echo "==> Signature:"
codesign -dv --verbose=4 "$BIN" 2>&1 | sed 's/^/    /'
echo "==> Entitlements:"
codesign -d --entitlements - --verbose=4 "$BIN" 2>&1 | sed 's/^/    /' || true
codesign --verify --strict --verbose=2 "$BIN"
echo "==> OK: $BIN"

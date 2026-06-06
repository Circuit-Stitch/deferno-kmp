#!/usr/bin/env bash
# Re-fetch the pinned Deferno OpenAPI spec and show what drifted.
#
# contracts/openapi-0.1.json is the contract of record for the client (ADR-0005, floor = 0.1).
# It is a *pinned snapshot* — drift from the live backend shows up here as a reviewable git diff
# instead of a silent runtime surprise. Run this when you suspect the backend has changed.
#
# Usage: contracts/refresh.sh [URL]      (default: http://localhost:3000/openapi.json)
set -euo pipefail
URL="${1:-http://localhost:3000/openapi.json}"
DIR="$(cd "$(dirname "$0")" && pwd)"
DEST="$DIR/openapi-0.1.json"
tmp="$(mktemp)"
curl -fsS "$URL" -o "$tmp"
# Pretty-print stably so diffs are semantic, not formatting noise.
python3 -m json.tool "$tmp" > "$DEST"
rm -f "$tmp"
echo "Refreshed $DEST from $URL"
git -C "$DIR/.." --no-pager diff -- "$DEST" || true

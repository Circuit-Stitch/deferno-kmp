#!/bin/sh
# Regenerates the iOS brand raster assets from the shared flame SVG (the source of truth):
#
#   1. AppIcon.appiconset/AppIcon-1024.png — the home-screen app icon: the flame composited centered
#      on the Deferno dark surface (#1F1B16, the paper-2 the Android adaptive-icon background uses) at
#      ~0.66 coverage (mirrors Android's adaptive safe zone). Opaque, NO alpha (App Store requirement).
#   2. Flame.imageset/Flame.png — the bare flame on a transparent background, trimmed to its artwork.
#      Shared by the LaunchScreen.storyboard (on the same #1F1B16 background) and the in-app `Brandmark`.
#
# iOS app icons must be raster PNG (the asset catalog doesn't accept SVG, and the vector Icon Composer
# `.icon` format needs Xcode 16+; this repo targets 15.2), so we rasterize the SVG once and let iOS
# derive the rest. The SVG is rasterized with `NSImage`, which decodes it natively *with a transparent
# background* and as a vector (crisp at any size) — `qlmanage`/QuickLook flattens the transparent page
# onto opaque white, which would leave a white box behind the flame. macOS only. Run from anywhere:
#   app/iosApp/scripts/generate-brand-assets.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
svg="$repo/core/designsystem/brand/flame.svg"
assets="$repo/app/iosApp/iosApp/Assets.xcassets"
icon="$assets/AppIcon.appiconset/AppIcon-1024.png"
flame="$assets/Flame.imageset/Flame.png"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/render.swift" <<'SWIFT'
import AppKit

// render <flame.svg> <icon-out.png> <flame-out.png>
let svgPath = CommandLine.arguments[1], iconOut = CommandLine.arguments[2], flameOut = CommandLine.arguments[3]
let cs = CGColorSpaceCreateDeviceRGB()

// Rasterize the SVG (vector, transparent background) to a hi-res CGImage via NSImage.
guard let svg = NSImage(contentsOfFile: svgPath) else { fputs("cannot load \(svgPath)\n", stderr); exit(1) }
let HI = 2048
let hc = CGContext(data: nil, width: HI, height: HI, bitsPerComponent: 8, bytesPerRow: 0,
                   space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
hc.interpolationQuality = .high
let g = NSGraphicsContext(cgContext: hc, flipped: false)
NSGraphicsContext.current = g
svg.draw(in: NSRect(x: 0, y: 0, width: HI, height: HI), from: .zero, operation: .sourceOver, fraction: 1.0)
NSGraphicsContext.current = nil
let flameHi = hc.makeImage()!

// Tight alpha bounding box of the flame artwork.
var px = [UInt8](repeating: 0, count: HI * 4 * HI)
let bc = CGContext(data: &px, width: HI, height: HI, bitsPerComponent: 8, bytesPerRow: HI * 4,
                   space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
bc.draw(flameHi, in: CGRect(x: 0, y: 0, width: HI, height: HI))
var minX = HI, minY = HI, maxX = 0, maxY = 0
for y in 0..<HI { for x in 0..<HI where px[(y * HI + x) * 4 + 3] > 8 {
    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
}}
let cw = maxX - minX + 1, ch = maxY - minY + 1
let flame = flameHi.cropping(to: CGRect(x: minX, y: minY, width: cw, height: ch)) ?? flameHi

func write(_ ctx: CGContext, _ to: String) {
    let data = NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!
    try! data.write(to: URL(fileURLWithPath: to))
}

// Icon: flame centered on the opaque dark surface, alpha stripped.
let canvas: CGFloat = 1024, coverage: CGFloat = 0.66
let oc = CGContext(data: nil, width: Int(canvas), height: Int(canvas), bitsPerComponent: 8,
                   bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
oc.setFillColor(red: 0x1F/255.0, green: 0x1B/255.0, blue: 0x16/255.0, alpha: 1)
oc.fill(CGRect(x: 0, y: 0, width: canvas, height: canvas))
oc.interpolationQuality = .high
let si = canvas * coverage / CGFloat(max(cw, ch)), idw = CGFloat(cw) * si, idh = CGFloat(ch) * si
oc.draw(flame, in: CGRect(x: (canvas - idw) / 2, y: (canvas - idh) / 2, width: idw, height: idh))
write(oc, iconOut)

// Flame: trimmed, transparent, longer side 1024.
let target: CGFloat = 1024
let sf = target / CGFloat(max(cw, ch)), fw = Int(CGFloat(cw) * sf), fh = Int(CGFloat(ch) * sf)
let fc = CGContext(data: nil, width: fw, height: fh, bitsPerComponent: 8, bytesPerRow: 0,
                   space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
fc.interpolationQuality = .high
fc.draw(flame, in: CGRect(x: 0, y: 0, width: fw, height: fh))
write(fc, flameOut)
SWIFT

mkdir -p "$(dirname "$icon")" "$(dirname "$flame")"
swift "$tmp/render.swift" "$svg" "$icon" "$flame"
echo "Wrote $icon"
echo "Wrote $flame"

# ---- External-provenance source marks (#280) -------------------------------------------------------
# The small GitHub / Google "G" the Item tree row renders ahead of the open-detail chevron — the
# iOS/macOS twin of the Android per-row source indicator (PR #279). Rasterized from the brand SVGs into
# an imageset in BOTH asset catalogs (iOS + macOS); Xcode catalogs take PNG, not SVG. GitHub is a
# monochrome silhouette flagged template (the row tints it to the calm ink); Google keeps its four brand
# colours (original — the colour is the signal). Rendered at the SVG's own aspect, no trim, so the glyph
# carries the same intrinsic padding it has on Android (both fit a 16pt box on the row).
mac_assets="$repo/app/macosApp/macosApp/Assets.xcassets"

cat > "$tmp/rendermark.swift" <<'SWIFT'
import AppKit
// rendermark <in.svg> <out.png> <longer-side-px>
let svgPath = CommandLine.arguments[1], out = CommandLine.arguments[2]
let n = CGFloat(Int(CommandLine.arguments[3])!)
guard let svg = NSImage(contentsOfFile: svgPath) else { fputs("cannot load \(svgPath)\n", stderr); exit(1) }
let s = svg.size, scale = n / max(s.width, s.height)
let w = Int((s.width * scale).rounded()), h = Int((s.height * scale).rounded())
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.interpolationQuality = .high
let g = NSGraphicsContext(cgContext: ctx, flipped: false)
NSGraphicsContext.current = g
svg.draw(in: NSRect(x: 0, y: 0, width: w, height: h), from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.current = nil
let data = NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!
try! data.write(to: URL(fileURLWithPath: out))
SWIFT

# emit_mark <name> <source-svg> <template-rendering-intent>
emit_mark() {
    name=$1 src=$2 intent=$3
    for cat in "$assets" "$mac_assets"; do
        set="$cat/$name.imageset"
        mkdir -p "$set"
        swift "$tmp/rendermark.swift" "$repo/core/designsystem/brand/$src" "$set/$name.png" 128
        cat > "$set/Contents.json" <<JSON
{
  "images" : [
    {
      "filename" : "$name.png",
      "idiom" : "universal"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  },
  "properties" : {
    "template-rendering-intent" : "$intent"
  }
}
JSON
        echo "Wrote $set/$name.png"
    done
}

emit_mark ic_source_github ic_source_github.svg template
emit_mark ic_source_google ic_source_google.svg original

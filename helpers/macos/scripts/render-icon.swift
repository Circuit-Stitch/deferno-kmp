// Renders the Deferno .iconset from the product launcher icon (a full-bleed square PNG):
// each size draws the source into a rounded-rect tile on Apple's macOS icon grid (tile = 824/1024
// of the canvas, corner radius = 185/824 of the tile) so the icon reads as a native macOS app icon
// in Notification Center / System Settings rather than an un-masked square.
//
// Usage: swift render-icon.swift <source.png> <out.iconset-dir>
// Invoked by scripts/wrap-app.sh; icon_512x512@2x is omitted (the source is 512 px).
import AppKit

let args = CommandLine.arguments
guard args.count == 3, let source = NSImage(contentsOfFile: args[1]) else {
    FileHandle.standardError.write(Data("usage: render-icon.swift <source.png> <out-dir>\n".utf8))
    exit(2)
}
let outDir = URL(fileURLWithPath: args[2], isDirectory: true)

let entries: [(name: String, pixels: Int)] = [
    ("icon_16x16", 16), ("icon_16x16@2x", 32),
    ("icon_32x32", 32), ("icon_32x32@2x", 64),
    ("icon_128x128", 128), ("icon_128x128@2x", 256),
    ("icon_256x256", 256), ("icon_256x256@2x", 512),
    ("icon_512x512", 512),
]

for entry in entries {
    let px = entry.pixels
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
    ) else { exit(3) }
    rep.size = NSSize(width: px, height: px)

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let tile = CGFloat(px) * 824.0 / 1024.0
    let origin = (CGFloat(px) - tile) / 2.0
    let tileRect = NSRect(x: origin, y: origin, width: tile, height: tile)
    let radius = tile * 185.0 / 824.0
    NSBezierPath(roundedRect: tileRect, xRadius: radius, yRadius: radius).addClip()
    source.draw(in: tileRect, from: .zero, operation: .sourceOver, fraction: 1.0)
    NSGraphicsContext.restoreGraphicsState()

    guard let png = rep.representation(using: .png, properties: [:]) else { exit(3) }
    try! png.write(to: outDir.appendingPathComponent("\(entry.name).png"))
}

import Deferno
import SwiftUI

// The "See the trees" tree atoms (macOS twin of the iOS `Atoms.swift` / Compose `TreeAtoms.kt`, #237 /
// ADR-0028 per-platform duplication). Only the subset the Item tree needs lives here — the connected-tree
// filigree tokens, kind accent, mono meta, and the collapsed-parent progress bar. Pure, stateless SwiftUI
// rendered off the already-mirrored `DefernoColors` tokens.

// MARK: - Tokens

enum Tree {
    /// Per-depth horizontal step of the tree rail + node indent (Compose `RailGutter`).
    static let railGutter: CGFloat = 22
    /// The leaf kind-dot diameter; the parent fold-disc is larger.
    static let leafDot: CGFloat = 11
    static let parentDisc: CGFloat = 24
    static let railStroke: CGFloat = 1.6
}

extension Font {
    /// The mono voice of the design system. The Compose side bundles IBM Plex Mono; the Apple targets use
    /// the system monospaced face — close enough that bundling the font is deferred.
    static func defernoMono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

/// The grove/kind accent for an Item (Compose `TreeAtoms.kindColor`): Task→primary, Habit→success,
/// Event→secondary, Chore→tertiary. `ItemKind` is matched through the Kotlin bridge (`itemKindsEqual`),
/// the established macOS idiom for comparing the bridged enum.
func kindColor(_ kind: ItemKind, _ colors: DefernoColors) -> Color {
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.habit) { return colors.success }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.event) { return colors.secondary }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.chore) { return colors.tertiary }
    return colors.primary // Task (+ any future kind)
}

// MARK: - Atoms

/// A small solid kind dot marking a grove/leaf (Compose `KindDot`).
struct KindDot: View {
    var color: Color
    var size: CGFloat = Tree.leafDot
    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

/// One-line mono metadata — dates, counts, "5 of 22" (Compose `MonoMeta`).
struct MonoMeta: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.defernoMono(12, weight: .medium))
            .foregroundStyle(colors.inkMuted)
            .lineLimit(1)
            .truncationMode(.tail)
    }
}

/// A thin completion bar for a collapsed parent (Compose `ProgressBarThin`).
struct ProgressBarThin: View {
    /// 0…1.
    let fraction: Double
    var height: CGFloat = 5
    @Environment(\.defernoColors) private var colors

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(colors.surfaceVariant)
                Capsule().fill(colors.primary)
                    .frame(width: max(0, min(1, fraction)) * geo.size.width)
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}

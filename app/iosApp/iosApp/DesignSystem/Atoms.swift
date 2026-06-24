import Deferno
import SwiftUI

// The "See the trees" shared atoms (iOS twin of core/designsystem `DefernoComponents.kt` + the Compose
// restyle, commit 2daef3a). Pure, stateless SwiftUI that renders off the already-mirrored `DefernoColors`
// tokens — no shared-component dependency. The screens compose these instead of ad-hoc styling so the
// restyle is consistent and one place owns each visual decision (calm parchment surfaces, mono eyebrows,
// connected-tree filigree, tap-to-complete dots).

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
    /// The mono voice of the design system. The Compose side bundles IBM Plex Mono; iOS uses the system
    /// monospaced face — close enough that bundling the font is deferred (ponytail: add the font only if
    /// the drift is ever rejected).
    static func defernoMono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

/// The grove/kind accent for an Item (Compose `TreeAtoms.kindColor`): Task→primary, Habit→success,
/// Event→secondary, Chore→tertiary. `ItemKind` bridges as a singleton class, so match by identity.
func kindColor(_ kind: ItemKind, _ colors: DefernoColors) -> Color {
    if kind == ItemKind.habit { return colors.success }
    if kind == ItemKind.event { return colors.secondary }
    if kind == ItemKind.chore { return colors.tertiary }
    return colors.primary // Task (+ any future kind)
}

// MARK: - Mono text atoms

/// An eyebrow band label — "YOUR DAY", "BRANCHES" (Compose `SectionLabel`): mono, semibold, uppercased,
/// loosely tracked, muted.
struct SectionLabel: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(11, weight: .semibold))
            .tracking(1.1)
            .foregroundStyle(colors.onSurfaceVariant)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A small amber eyebrow — "IF YOU'RE NOT SURE, START HERE" (Compose `Eyebrow`).
struct Eyebrow: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(10, weight: .semibold))
            .tracking(1.2)
            .foregroundStyle(colors.amberDeep)
    }
}

/// One-line mono metadata — dates, counts, "5 of 22" (Compose `MonoMeta`).
struct MonoMeta: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(text: String) { self.text = text }
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.defernoMono(12, weight: .medium))
            .foregroundStyle(colors.inkMuted)
            .lineLimit(1)
            .truncationMode(.tail)
    }
}

// MARK: - Status / completion dots

/// A small solid kind dot marking a grove/leaf (Compose `KindDot`).
struct KindDot: View {
    var color: Color
    var size: CGFloat = Tree.leafDot
    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

/// The round tap-to-complete control (Compose `CheckDot`): a hollow ring when open, a filled primary disc
/// with a check when done. Stateless — the caller wires `onToggle` to the working-state intent.
struct CheckDot: View {
    let checked: Bool
    var enabled: Bool = true
    var size: CGFloat = 24
    let onToggle: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: onToggle) {
            ZStack {
                if checked {
                    Circle().fill(colors.primary)
                    DefernoIcon.check.image(size: size * 0.55).foregroundStyle(colors.onPrimary)
                } else {
                    Circle().strokeBorder(enabled ? colors.outline : colors.outlineVariant, lineWidth: 1.6)
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(checked ? "Done" : "Not done")
        .accessibilityLabel("Mark done")
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

// MARK: - Action atoms

/// The big filled call-to-action (Compose `PrimaryActionButton`) — e.g. drawer "New task".
struct PrimaryActionButton: View {
    let title: String
    var subtitle: String? = nil
    var icon: DefernoIcon? = nil
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon { icon.image(size: 20).foregroundStyle(colors.onPrimary) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(colors.onPrimary)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(colors.onPrimary.opacity(0.85))
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(colors.primary, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A secondary tonal action card (drawer "Brain dump") — same shape as `PrimaryActionButton`, calm tone.
struct TonalActionButton: View {
    let title: String
    var subtitle: String? = nil
    var icon: DefernoIcon? = nil
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon { icon.image(size: 20).foregroundStyle(colors.onSurface) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(colors.onSurface)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(colors.inkMuted)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The amber "▶ Start" pill (Compose `StartPill`) — the suggestion card's act.
struct StartPill: View {
    var title: String = "Start"
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                DefernoIcon.play.image(size: 12).foregroundStyle(colors.onPrimary)
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(colors.onPrimary)
            }
            .padding(.horizontal, 16).padding(.vertical, 9)
            .background(colors.primary, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// A calm primary-tinted text link — "See everything ›" (Compose `TextLink`).
struct TextLink: View {
    let title: String
    var trailingChevron: Bool = false
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold))
                if trailingChevron { DefernoIcon.chevronRight.image(size: 12) }
            }
            .foregroundStyle(colors.primary)
        }
        .buttonStyle(.plain)
    }
}

/// A dashed "＋ Add …" affordance (Compose `DashedAddButton`) — "Add from the forest", "Add a tree".
struct DashedAddButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                DefernoIcon.plus.image(size: 14)
                Text(title).font(.subheadline)
            }
            .foregroundStyle(colors.inkMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.2, dash: [5, 4]))
                    .foregroundStyle(colors.outline)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A calm, display-only search well that opens the global Search overlay on tap (Compose
/// `SearchBarDisplay`) — "Search all your trees…".
struct SearchBarDisplay: View {
    var placeholder: String = "Search all your trees…"
    let onTap: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                DefernoIcon.search.image(size: 16).foregroundStyle(colors.inkMuted)
                Text(placeholder).font(.subheadline).foregroundStyle(colors.inkMuted)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Search")
        .accessibilityAddTraits(.isSearchField)
    }
}

/// A pill segmented control (Compose `SegmentedFilter`) — the "In today / Active / All" tree presets.
struct SegmentedFilter: View {
    let options: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options.indices, id: \.self) { i in
                let selected = i == selectedIndex
                Button { onSelect(i) } label: {
                    Text(options[i])
                        .font(.footnote.weight(selected ? .semibold : .regular))
                        .foregroundStyle(selected ? colors.onPrimary : colors.inkMuted)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(selected ? colors.primary : colors.surfaceVariant, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
    }
}

/// The tone of a `TreeChip`.
enum ChipTone { case neutral, accent, warn }

/// A small mono badge for priority / kind / status (Compose `TreeChip`).
struct TreeChip: View {
    let text: String
    var tone: ChipTone = .neutral
    @Environment(\.defernoColors) private var colors

    private var fg: Color {
        switch tone {
        case .neutral: return colors.inkMuted
        case .accent: return colors.amberDeep
        case .warn: return colors.error
        }
    }
    private var bg: Color {
        switch tone {
        case .neutral: return colors.surfaceVariant
        case .accent: return colors.primaryContainer
        case .warn: return colors.errorContainer
        }
    }

    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(10, weight: .semibold))
            .tracking(0.6)
            .foregroundStyle(fg)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(bg, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

/// A drill breadcrumb trail (Compose `Breadcrumb`) — kept minimal; the detail header uses it.
struct Breadcrumb: View {
    let crumbs: [String]
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(spacing: 4) {
            ForEach(crumbs.indices, id: \.self) { i in
                Text(crumbs[i]).font(.defernoMono(11)).foregroundStyle(colors.inkMuted).lineLimit(1)
                if i < crumbs.count - 1 {
                    DefernoIcon.chevronRight.image(size: 9).foregroundStyle(colors.inkMuted.opacity(0.7))
                }
            }
        }
    }
}

import Deferno
import SwiftUI

// The "See the trees" shared atoms (macOS twin of the iOS `Atoms.swift` / Compose `TreeAtoms.kt` +
// `DefernoComponents.kt`, #237 / ADR-0028 per-platform duplication). Pure, stateless SwiftUI rendered off
// the already-mirrored `DefernoColors` role tokens — never AppKit system colours, so the brand palettes
// (Deferno / Mono × light/dark) re-theme live like every other Deferno-styled View.
//
// The screens compose these instead of hand-rolling their own styling, so the restyle stays consistent and
// one place owns each visual decision (calm parchment surfaces, mono eyebrows, connected-tree filigree,
// click-to-complete dots). Ported near-verbatim from the iOS twin; where macOS differs the divergence is
// deliberate and called out at the site: desktop pointer targets (`Layout.minTouchTarget` = 28, not the
// touch-first 48), hover affordances on the flat click surfaces, and `.help()` tooltips.
//
// **Most of these atoms have no call site yet, on purpose.** They are a *staged* port landed ahead of the
// screens that will compose them — the remaining macOS parity surfaces tracked by **#368** — so each screen
// reaches for an existing atom instead of growing a private variant that then has to be reconciled. That is
// the named consumer, not speculation: an atom still unused when #368 closes should be deleted, not kept.
// (The already-shipped consumers cite their own issues at the call sites: the Item tree #227/#231/#290, the
// "See the trees" restyle #237.)

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

/// The **spoken** word for an Item's kind (Compose `TreeAtoms.kindA11yLabel`) — the lowercase noun
/// "task"/"habit"/"chore"/"event". Colour is the only kind cue on a row, and colour is invisible to
/// VoiceOver and unreliable for a colour-blind reader, so any row that shows a kind dot owes its
/// accessibility label this word (#393). Kinds are matched through `itemKindsEqual`, the same bridge
/// idiom `kindColor` uses — `==` on the bridged enum does not work here.
///
/// Deliberately NOT `kindDisplayLabel(...).lowercased()`: this is exactly the rule `SectionLabel`,
/// `Eyebrow` and `DependencyBadge` are built around — an uppercased visual string must never be what
/// VoiceOver reads. The catalog carries two separate families because the transform isn't mechanical
/// (Devanagari has no case, so hi is the same word in both).
func kindA11yLabel(_ kind: ItemKind) -> String {
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.habit) { return L.string("tasks_kind_a11y_habit") }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.event) { return L.string("tasks_kind_a11y_event") }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.chore) { return L.string("tasks_kind_a11y_chore") }
    return L.string("tasks_kind_a11y_task") // Task (+ any future kind)
}

/// The **visible** all-caps kind marker (Compose `TreeAtoms.kindLabel`) — "TASK", "HÁBITO". The
/// resource holds the exact rendered text, so never `.uppercased()` it here. Pair every use with
/// `kindA11yLabel` on whatever element VoiceOver reads.
func kindDisplayLabel(_ kind: ItemKind) -> String {
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.habit) { return L.string("tasks_kind_label_habit") }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.event) { return L.string("tasks_kind_label_event") }
    if ShellBridgeKt.itemKindsEqual(a: kind, b: ItemKind.chore) { return L.string("tasks_kind_label_chore") }
    return L.string("tasks_kind_label_task") // Task (+ any future kind)
}

// MARK: - Mono text atoms

/// An eyebrow band label — "YOUR DAY", "BRANCHES" (Compose `SectionLabel`): mono, semibold, uppercased,
/// loosely tracked, muted. The one section header for the restyled surfaces — a pane title is `PaneHeader`.
///
/// The uppercasing is a **visual** transform, so the natural-case string is restored as the accessibility
/// label (#368) — the same rule `DependencyBadge` is built around (see the closing note in this file: its
/// `semanticLabel` is REQUIRED "so the uppercased glyphs are never what VoiceOver reads"). Here it matters
/// twice over: `.isHeader` means VoiceOver announces this string *as a heading*, and an all-caps heading is
/// read either letter-by-letter or in the shouted voice depending on the user's verbosity settings.
struct SectionLabel: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(11, weight: .semibold))
            .tracking(1.1)
            .foregroundStyle(colors.onSurfaceVariant)
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A small amber eyebrow — "IF YOU'RE NOT SURE, START HERE" (Compose `Eyebrow`).
///
/// Same split as `SectionLabel` above: uppercased glyphs for the eye, the natural-case string for VoiceOver
/// (#368) — the `DependencyBadge` rule, applied to every uppercasing atom rather than just the one that
/// happened to need it first.
struct Eyebrow: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(10, weight: .semibold))
            .tracking(1.2)
            .foregroundStyle(colors.amberDeep)
            .accessibilityLabel(text)
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

// MARK: - Status / completion dots

/// A small solid kind dot marking a grove/leaf (Compose `KindDot`).
struct KindDot: View {
    var color: Color
    var size: CGFloat = Tree.leafDot
    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

/// The round click-to-complete control (Compose `CheckDot`): a hollow ring when open, a filled primary disc
/// with a check when done. Stateless — the caller wires `onToggle` to the working-state intent.
///
/// The drawn disc keeps the iOS diameter, but the *hit* area is floored at the desktop pointer target
/// (`Layout.minTouchTarget`) rather than the touch-first 48pt — a mouse hits a tighter target just fine,
/// and the row density stays compact.
struct CheckDot: View {
    let checked: Bool
    var enabled: Bool = true
    var size: CGFloat = Layout.checkDotSize
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
            .frame(
                width: max(size, Layout.minTouchTarget),
                height: max(size, Layout.minTouchTarget)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(checked ? L.string("calendar_action_done") : L.string("common_state_not_done"))
        .accessibilityLabel(L.string("tasks_menu_mark_done"))
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

/// The big filled call-to-action (Compose `PrimaryActionButton`) — e.g. the drawer's "New task".
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
        // `.plain` for the same reason `SelectableChip` needs it: macOS's default button style ignores
        // `.foregroundStyle` on a Button's title and tints it with the accent, washing the label out on
        // the filled capsule. Colouring an explicit `Text` + `.plain` is the fix.
        .buttonStyle(.plain)
    }
}

/// A secondary tonal action card (the drawer's "Brain dump") — same shape as `PrimaryActionButton`, calm tone.
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
    var title: String = L.string("common_start")
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                DefernoIcon.play.image(size: 12).foregroundStyle(colors.onPrimary)
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(colors.onPrimary)
            }
            .padding(.horizontal, 16).padding(.vertical, 9)
            .frame(minHeight: Layout.minTouchTarget)
            .background(colors.primary, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// A calm primary-tinted text link — "See everything ›" (Compose `TextLink`). Floored at the desktop
/// pointer target so a bare word still presents a clickable row (`Layout.minTouchTarget`).
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
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A dashed "＋ Add …" affordance (Compose `DashedAddButton`) — "Add from the forest", "Add a tree".
/// A flat, borderless click surface, so macOS gets a hover fill (the pointer cue that the iOS twin has no
/// need for) — the same `surfaceVariant` lift the Trail's tappable history rows use.
struct DashedAddButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.defernoColors) private var colors
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                DefernoIcon.plus.image(size: 14)
                Text(title).font(.subheadline)
            }
            .foregroundStyle(colors.inkMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(hovering ? colors.surfaceVariant : .clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(style: StrokeStyle(lineWidth: 1.2, dash: [5, 4]))
                    .foregroundStyle(colors.outline)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

/// A calm, display-only search well that opens the global Search overlay on click (Compose
/// `SearchBarDisplay`) — "Search all your trees…". It is not a real text field, so it keeps the
/// `.isSearchField` trait (VoiceOver announces the affordance it stands in for) and gains the macOS
/// pointer cues: a hover lift onto `surfaceCard` and a `.help()` tooltip.
struct SearchBarDisplay: View {
    var placeholder: String = L.string("search_placeholder_trees")
    let onTap: () -> Void
    @Environment(\.defernoColors) private var colors
    @State private var hovering = false

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                DefernoIcon.search.image(size: 16).foregroundStyle(colors.inkMuted)
                Text(placeholder).font(.subheadline).foregroundStyle(colors.inkMuted)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(
                hovering ? colors.surfaceCard : colors.surfaceVariant,
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help(L.string("common_search"))
        .accessibilityLabel(L.string("common_search"))
        .accessibilityAddTraits(.isSearchField)
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

// MARK: - Deliberately absent (macOS already owns the substitute)

// Two iOS atoms have NO macOS twin here on purpose — macOS grew its own equivalent first and the shipping
// call sites are already on it. Porting the iOS name would mean two ways to draw the same pixels, so the
// upcoming screen ports should reach for the macOS type instead:
//
//   iOS `SegmentedFilter(options:selectedIndex:onSelect:)`
//     → `SelectableChip` (Common/CommonViews.swift) in an `HStack`. It exists because macOS's default
//       button style tints a Button's *title* with the accent and washes the label out; it also carries
//       the `prominence`/`compact` axes the desktop filter rows want (`.low` + `compact: true` is the
//       segment look). Already used by the Tasks tree, Search, Settings, New and Feedback.
//
//   iOS `TreeChip(text:tone:)` + `ChipTone`
//     → `DependencyBadge` (Common/CommonViews.swift), the same mono badge with a REQUIRED `semanticLabel`
//       so the uppercased glyphs are never what VoiceOver reads. It covers all three tones — `.neutral`,
//       `.accent` and (since #368 G17c) `.warn`, the error-toned Task-detail subtask "Blocked" chip.

// MARK: - Date / time pickers (#348 follow-up)

/// A **nullable** date (or date+time) row — the macOS idiom for every field that used to ask for a
/// hand-typed ISO string. Inline `DatePicker` in the desktop `.field` style (an editable field + stepper),
/// deliberately NOT iOS's chip-opens-a-graphical-calendar: that construction exists to fight iPhone
/// popover-to-sheet adaptation, and a calendar grid is the wrong idiom next to a keyboard — the same call
/// the Task-detail WHEN row already made. `.labelsHidden()` is required: an empty `""` title still reserves
/// leading label width on macOS and mis-aligns the row.
///
/// **Optionality is the point.** Deferno's date fields are overwhelmingly nullable — an undated Task, an
/// open-ended Event, an open edge of a search range — and a picker that always displays *something* cannot
/// say "no date". So an unset value renders as a muted "—" plus an Add affordance rather than a field
/// showing a day nobody chose, and `onClear` (when the field is genuinely clearable) puts it back. Pass
/// `onAdd: nil` for a required field and `onClear: nil` for one that cannot be emptied.
///
/// The value crosses as **epoch seconds** with `-1` for unset, matching the Kotlin bridge seams — see the
/// picker section of `ShellBridge.kt`, which owns the device-zone calendar arithmetic for both platforms.
struct OptionalDatePickerRow: View {
    let label: String
    let accessibilityLabel: String
    let epochSeconds: Double
    /// `[.date]` for a day-only field; `[.date, .hourAndMinute]` when the value carries a clock.
    var components: DatePickerComponents = [.date]
    let onPick: (Double) -> Void
    var onAdd: (() -> Void)?
    var onClear: (() -> Void)?

    @Environment(\.defernoColors) private var colors

    private var isSet: Bool { epochSeconds >= 0 }

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
            Spacer(minLength: 8)
            if isSet {
                DatePicker(
                    "",
                    selection: Binding(
                        get: { Date(timeIntervalSince1970: epochSeconds) },
                        set: { onPick($0.timeIntervalSince1970) }
                    ),
                    // What makes the bridge's `-1` "unset" sentinel genuinely out of band. Both sides
                    // test *negative means unset*, and `.field` is typeable, so without a floor someone
                    // could enter a pre-1970 day that round-trips to "—". No deadline, event window or
                    // search edge in this app is legitimately that old. The floor is epoch +1 day, not
                    // epoch 0: a bound of 0 renders as "12/31/1969" everywhere west of UTC (it is
                    // 1970-01-01 *UTC*), which reads like the very bug the bound exists to prevent.
                    in: Date(timeIntervalSince1970: 86_400)...,
                    displayedComponents: components
                )
                .datePickerStyle(.field)
                .labelsHidden()
                .accessibilityLabel(accessibilityLabel)
                if let onClear {
                    // The spoken name is the purpose-built noun ("Event start"), not the visible
                    // verb-conjugated column title ("Starts") — "Clear Starts" reads as broken English.
                    Button(L.string("common_clear"), action: onClear)
                        .font(.footnote)
                        .accessibilityLabel(L.format("common_clear_named_cd", accessibilityLabel))
                }
            } else {
                Text("—").foregroundStyle(colors.inkMuted)
                if let onAdd {
                    Button(L.string("common_add"), action: onAdd)
                        .font(.subheadline)
                        .accessibilityLabel(L.format("common_add_named_cd", accessibilityLabel))
                }
            }
        }
        .frame(minHeight: Layout.minTouchTarget)
    }
}

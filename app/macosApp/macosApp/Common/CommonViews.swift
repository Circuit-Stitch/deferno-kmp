import Deferno
import SwiftUI

// Shared, stateless building blocks for the Tasks + Plan Views (#51) — the SwiftUI counterparts of
// the Android `TaskUi`/`PlanUi` atoms. Calm flat lists over dense cards, plain labels, large touch
// targets, and VoiceOver semantics on every interactive element (design-principles.md).

/// The shared "Session expired — sign in again" banner (#297) — the SwiftUI counterpart of the design
/// system's `SessionExpiredBanner`, the same affordance Profile shows. The read surfaces render it when
/// an authenticated request `401`s, so a dead token can't masquerade as a stale cache; `onSignIn` routes
/// toward re-auth (Profile), and the next successful sync clears the flag.
struct SessionExpiredBanner: View {
    var message: String = L.string("common_session_expired_message")
    var action: String = L.string("common_sign_in_again")
    let onSignIn: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(spacing: 8) {
            Text(message).font(.subheadline).foregroundStyle(colors.onSurface)
            Spacer(minLength: 8)
            Button(action, action: onSignIn).buttonStyle(.bordered)
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.errorContainer)
        .accessibilityElement(children: .combine)
    }
}

/// A small, readable badge for a Task's working state. The label is plain text so it reads correctly
/// under VoiceOver; colour is reinforcement, never the sole signal (WCAG).
struct WorkingStateBadge: View {
    let state: WorkingState

    var body: some View {
        Text(state.label)
            .font(.caption.weight(.medium))
            .foregroundStyle(state.badgeForeground)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(state.badgeBackground, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .accessibilityElement()
            .accessibilityLabel(L.format("common_status_a11y", state.label))
    }
}

/// A small mono dependency badge on a tree row (#290) — the macOS twin of the iOS `TreeChip` (which
/// macOS lacks). `.neutral` is the quiet "Blocked" pill (muted ink); `.accent` is the amber "Blocker"
/// badge. `semanticLabel` is spoken in place of the uppercased glyphs.
struct DependencyBadge: View {
    enum Tone { case neutral, accent }

    let text: String
    var tone: Tone = .neutral
    let semanticLabel: String
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Text(text.uppercased())
            .font(.defernoMono(10, weight: .semibold))
            .tracking(0.6)
            .foregroundStyle(tone == .accent ? colors.amberDeep : colors.inkMuted)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(
                tone == .accent ? colors.primaryContainer : colors.surfaceVariant,
                in: RoundedRectangle(cornerRadius: 5, style: .continuous)
            )
            .accessibilityElement()
            .accessibilityLabel(semanticLabel)
    }
}

/// A tappable Task row: title, optional human `ref` in a monospaced font, a pinned marker, and the
/// working-state badge. Flat (the list supplies the divider), one large touch target, spoken as
/// "<title>, <status>". Shared by the Task list, the tree, and (without the pin) the Plan.
struct TaskRow: View {
    let task: Task
    var showsPin: Bool = true
    let onTap: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        if showsPin && task.pinned {
                            Text("★").foregroundStyle(.orange)
                        }
                        Text(task.title)
                            .font(.headline)
                            // Blocked mutes (but doesn't strike) like the tree row (#290/#292); a blocked
                            // item manually added to the plan is retained, just flagged.
                            .foregroundStyle(task.blocked ? colors.inkMuted : colors.onSurface)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                        if task.blocked {
                            DependencyBadge(text: L.string("common_blocked"), tone: .neutral, semanticLabel: L.string("common_blocked"))
                        }
                    }
                    if let ref = task.ref {
                        Text(ref)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 12)
                WorkingStateBadge(state: task.workingState)
            }
            .frame(minHeight: Layout.rowMinHeight)
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, Layout.rowVerticalPadding)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(task.blocked ? "\(task.title), blocked, \(task.workingState.label)" : "\(task.title), \(task.workingState.label)")
        .accessibilityHint(task.pinned && showsPin ? L.string("common_pinned") : "")
    }
}

/// One node of the Tasks **Item tree** (#227, ADR-0034), restyled to the "See the trees" connected-tree
/// filigree (#237, the macOS twin of the iOS `ItemRowView`). A leading curvy **rail** + kind **node**
/// (the leaf kind-dot or a parent fold-disc with a rotating chevron) drives the fold; a body tap also
/// toggles a parent's fold (a leaf body is inert). The trailing › opens detail (Task kind only — the other
/// kinds have no detail surface yet). A collapsed parent shows a `descendantDone/descendantTotal` meta +
/// progress bar (Tasks only); a terminal (Done/Dropped/Archived) row is de-emphasized + struck through.
/// Stateless: the handlers take their params from the row, never from observed state.
struct ItemRowView: View {
    let row: ItemRow
    let onToggleExpand: (String, Bool) -> Void
    let onOpenDetail: (String, ItemKind) -> Void

    @Environment(\.defernoColors) private var colors

    private var isTask: Bool { BridgeKt.itemKindIsTask(kind: row.item.kind) }

    /// The connecting-rail / node accent — the row's kind colour (also tinted for the rail spine).
    private var accent: Color { kindColor(row.item.kind, colors) }

    /// `(done, total)` for a collapsed Task parent carrying server-computed subtree counts; nil otherwise
    /// (an expanded parent, a leaf, or a recurring kind with no subtree counts).
    private var progress: (done: Int, total: Int)? {
        guard row.hasChildren, !row.isExpanded, let total = row.item.descendantTotal else { return nil }
        return (Int(row.item.descendantDone?.intValue ?? 0), Int(total.intValue))
    }

    var body: some View {
        HStack(spacing: 0) {
            // Leading rail+node region: the curvy spine (underlay) with the kind node landed at its column.
            ZStack(alignment: .topLeading) {
                TreeRail(
                    spine: row.spine.map { $0.boolValue },
                    depth: Int(row.depth),
                    hasChildren: row.hasChildren,
                    isExpanded: row.isExpanded,
                    color: accent
                )
                TreeNode(
                    kindColor: accent,
                    hasChildren: row.hasChildren,
                    isExpanded: row.isExpanded,
                    onToggle: { if row.hasChildren { onToggleExpand(row.item.id, row.isExpanded) } }
                )
                .frame(maxHeight: .infinity)
                .offset(x: TreeGeometry.nodeCenterX(depth: Int(row.depth)) - Tree.parentDisc / 2)
            }
            .frame(width: TreeGeometry.leadingWidth(depth: Int(row.depth)))

            // Title (+ collapsed progress meta). A body tap toggles a parent's fold; a leaf body is inert.
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.title)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .strikethrough(row.item.isTerminal)
                    // Terminal strikes + mutes; a `blocked` row mutes WITHOUT the strike — a distinct
                    // "blocked, not finished" read (mirrors Compose `ItemTreeRow`, #290).
                    .foregroundStyle((row.item.isTerminal || row.item.blocked) ? colors.inkMuted : colors.onSurface)
                if let progress {
                    MonoMeta(L.format("tasks_progress_fraction", progress.done, progress.total))
                        .padding(.top, 2)
                    if progress.total > 0 {
                        ProgressBarThin(fraction: Double(progress.done) / Double(progress.total))
                            .padding(.top, 2)
                    }
                }
            }
            .padding(.leading, 8)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if row.hasChildren { onToggleExpand(row.item.id, row.isExpanded) } }

            // Dependency badges (#290), ahead of the source mark: a quiet "Blocked" pill (blocked rows only
            // reach here when "Show blocked" is on, else they're pruned in the shared flatten) and an amber
            // "Blocker" badge marking a row that gates ≥1 other. Each carries its own VoiceOver label.
            if row.item.blocked {
                DependencyBadge(text: L.string("common_blocked"), tone: .neutral, semanticLabel: L.string("common_blocked"))
                    .padding(.horizontal, 4)
            }
            if row.item.isBlocker {
                DependencyBadge(text: L.string("tasks_badge_blocker"), tone: .accent, semanticLabel: L.string("tasks_badge_blocker_a11y"))
                    .padding(.horizontal, 4)
            }

            // External-provenance mark (GitHub / Google), ahead of the chevron — the mirror of the Android
            // placement (#279/#280). Absent for a native Deferno item (`source == nil`, the common case).
            if let source = row.item.source {
                SourceMark(source: source).padding(.horizontal, 4)
            }

            // Trailing › — opens detail; Task kind only (the other kinds have no detail surface yet).
            if isTask {
                Button {
                    onOpenDetail(row.item.id, row.item.kind)
                } label: {
                    DefernoIcon.chevronRight.image(size: 16)
                        .foregroundStyle(colors.inkMuted)
                        .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", row.item.title))
            }
        }
        .padding(.horizontal, Layout.gutter)
        .frame(minHeight: Layout.rowMinHeight)
        .opacity(row.item.isTerminal ? 0.5 : 1.0)
    }
}

/// The external-provenance mark on a tree row (#280) — the iOS/macOS twin of the Android `SourceIndicator`
/// (PR #279). A small brand glyph just before the open-detail ›, the sole cue of where an item came from,
/// so it carries its own VoiceOver label. GitHub is the Invertocat tinted to the calm row ink (`.template`
/// — it reads as filigree like the other row glyphs); Google is the canonical four-colour "G" rendered
/// untinted (the colour *is* the signal). The brand PNGs are rasterized from
/// core/designsystem/brand/ic_source_{github,google}.svg by `scripts/generate-brand-assets.sh`.
struct SourceMark: View {
    let source: ItemSource
    @Environment(\.defernoColors) private var colors

    private static let size: CGFloat = 16

    var body: some View {
        if source == ItemSource.gitHub {
            Image("ic_source_github")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: Self.size, height: Self.size)
                .foregroundStyle(colors.inkMuted)
                .accessibilityLabel(L.string("tasks_source_from_github"))
        } else {
            Image("ic_source_google")
                .resizable()
                .scaledToFit()
                .frame(width: Self.size, height: Self.size)
                .accessibilityLabel(L.string("tasks_source_from_google_calendar"))
        }
    }
}

/// A calm, single-line pane header: a title (a VoiceOver heading) with an optional leading **Back**
/// affordance and trailing actions. Used by every pane so placement stays predictable.
struct PaneHeader<Trailing: View>: View {
    let title: String
    let onBack: (() -> Void)?
    /// Shows the Deferno flame brand mark before the title — set on the Plan home (the app's calm
    /// entry pane), left off elsewhere so it brands the home without repeating on every pane.
    let showsBrand: Bool
    let trailing: () -> Trailing

    init(title: String, onBack: (() -> Void)? = nil, showsBrand: Bool = false, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.onBack = onBack
        self.showsBrand = showsBrand
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: 8) {
            if let onBack {
                Button(L.string("common_back"), action: onBack)
                    .frame(minHeight: Layout.minTouchTarget)
            }
            if showsBrand {
                Brandmark()
            }
            Text(title)
                .font(.title2.weight(.semibold))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            trailing()
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 56)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

extension PaneHeader where Trailing == EmptyView {
    init(title: String, onBack: (() -> Void)? = nil, showsBrand: Bool = false) {
        self.init(title: title, onBack: onBack, showsBrand: showsBrand) { EmptyView() }
    }
}

/// The Deferno brand mark: the flame logo — the shared `core/designsystem/brand/flame.svg` rasterized
/// into the `Flame` image asset by `scripts/generate-brand-assets.sh`, the same flame as the app icon
/// and launch screen. Sized to sit beside a `PaneHeader` title; spoken as "Deferno" for VoiceOver. The
/// flame is red line-art over white; on the header's `systemBackground` the white reads as the surface,
/// so it shows as a clean red mark in light mode and the full red/white flame in dark mode.
struct Brandmark: View {
    var height: CGFloat = 28

    var body: some View {
        Image("Flame")
            .resizable()
            .scaledToFit()
            .frame(width: height, height: height)
            .accessibilityLabel(L.string("common_app_name"))
    }
}

/// Gentle, non-judgmental empty state (design-principles.md): a kind title + supportive body, centered.
struct EmptyStateView: View {
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.title3.weight(.semibold))
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A calm, **static** "working…" strip — the reduced-motion alternative to an animated spinner
/// (design-principles.md). Plain text, announced to VoiceOver.
struct LoadingStrip: View {
    let label: String

    var body: some View {
        Text(label)
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(Color(nsColor: .underPageBackgroundColor))
            .accessibilityElement()
            .accessibilityLabel(label)
    }
}

/// A selectable capsule **chip** — the category/kind pickers, the settings + search filter segments, and
/// the task status picker all render through this one type. It exists because macOS's default button style
/// ignores `.foregroundStyle` on a `Button`'s *title* and tints it with the accent, washing the label out
/// on the dark capsule (the unreadable chips). Coloring an explicit `Text` + `.buttonStyle(.plain)` is the
/// fix; centralizing it keeps every chip readable, on-theme, and consistent for free.
///
/// `prominence` picks the selected fill: `.high` = the bold brand `primary` (the New/Feedback pickers),
/// `.low` = the softer `primaryContainer` (the settings + search filter segments). `compact` is the denser
/// footnote size used by the filter rows.
struct SelectableChip: View {
    enum Prominence { case high, low }

    let label: String
    let selected: Bool
    var prominence: Prominence = .high
    var compact: Bool = false
    /// Override the spoken label (e.g. the status picker's "…, current working state"); defaults to `label`.
    var accessibilityLabel: String?
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(compact ? .footnote : .subheadline)
                .foregroundStyle(foreground)
                .padding(.horizontal, compact ? 10 : 12)
                .padding(.vertical, compact ? 6 : 8)
                .frame(minHeight: Layout.minTouchTarget)
                .background(fill, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var fill: Color {
        guard selected else { return colors.surfaceVariant }
        return prominence == .high ? colors.primary : colors.primaryContainer
    }

    // Selected + high prominence is the only case that flips onto the dark `onPrimary` ink; everything
    // else (unselected, or the soft `primaryContainer` fill) reads on the light `onSurface` ink.
    private var foreground: Color {
        selected && prominence == .high ? colors.onPrimary : colors.onSurface
    }
}

extension Task {
    /// Stable String identity for SwiftUI list diffing. `Task.id` is an erased value class (opaque
    /// `Any` in Swift), so the Kotlin bridge unwraps it to the underlying UUID String.
    var stableKey: String { BridgeKt.taskKey(task: self) }
}

import Deferno
import SwiftUI

// Shared, stateless building blocks for the Tasks + Plan Views (#51) — the SwiftUI counterparts of
// the Android `TaskUi`/`PlanUi` atoms. Calm flat lists over dense cards, plain labels, large touch
// targets, and VoiceOver semantics on every interactive element (design-principles.md).

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
            .accessibilityLabel("Status: \(state.label)")
    }
}

/// A tappable Task row: title, optional human `ref` in a monospaced font, a pinned marker, and the
/// working-state badge. Flat (the list supplies the divider), one large touch target, spoken as
/// "<title>, <status>". Shared by the Task list, the tree, and (without the pin) the Plan.
struct TaskRow: View {
    let task: Task
    var showsPin: Bool = true
    let onTap: () -> Void

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
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
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
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(task.title), \(task.workingState.label)")
        .accessibilityHint(task.pinned && showsPin ? "Pinned" : "")
    }
}

/// One node of the Tasks **Item tree** (#227, #231, ADR-0034) — the "See the trees" connected-tree row
/// (iOS twin of feature/tasks/ui `ItemTreeRow`/`TreeAtoms.kt`). A curvy [TreeRail] hangs each child off
/// its parent in a calm tint of the row's kind accent and lands its elbow in the kind node; the [TreeNode]
/// is the kind dot (leaf) or an accent fold-disc with a rotating chevron (parent) — tapping it toggles a
/// parent's fold (a leaf node is decorative). A collapsed Task parent shows a `done of total` MonoMeta + a
/// thin progress bar; the trailing › opens detail (Task kind only — the other kinds have no detail surface
/// yet). A terminal (Done/Dropped/Archived) row is de-emphasized. Stateless: the handlers take their params
/// from the row, never from observed state.
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
                    MonoMeta("\(progress.done) of \(progress.total)")
                        .padding(.top, 2)
                    if progress.total > 0 {
                        ProgressBarThin(fraction: Double(progress.done) / Double(progress.total))
                            .padding(.top, 2)
                    }
                }
            }
            .padding(.leading, 8)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if row.hasChildren { onToggleExpand(row.item.id, row.isExpanded) } }

            // Dependency badges (#290), ahead of the source mark: a quiet "Blocked" pill (the de-emphasis
            // state's at-a-glance + VoiceOver carrier — blocked rows only reach here when "Show blocked" is
            // on, else they're pruned in the shared flatten) and an amber "Blocker" badge marking a row that
            // gates ≥1 other. Each clears its own label so VoiceOver reads one phrase, not the uppercased text.
            if row.item.blocked {
                TreeChip(text: "Blocked", tone: .neutral)
                    .padding(.horizontal, 4)
                    .accessibilityLabel("Blocked")
            }
            if row.item.isBlocker {
                TreeChip(text: "Blocker", tone: .accent)
                    .padding(.horizontal, 4)
                    .accessibilityLabel("Blocks other items")
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
                    DefernoIcon.chevronRight.image(size: 20)
                        .foregroundStyle(colors.inkMuted)
                        .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(row.item.title)")
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
                .accessibilityLabel("From GitHub")
        } else {
            Image("ic_source_google")
                .resizable()
                .scaledToFit()
                .frame(width: Self.size, height: Self.size)
                .accessibilityLabel("From Google Calendar")
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
                Button("Back", action: onBack)
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
        .background(Color(.systemBackground))
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
            .accessibilityLabel("Deferno")
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
            .background(Color(.secondarySystemBackground))
            .accessibilityElement()
            .accessibilityLabel(label)
    }
}

/// A pushed secondary screen in the compact-width Tasks `NavigationStack` (ADR-0007). Detail is the only
/// secondary slot now — the tree is the primary pane (the stack root) — so there is a single case.
enum TaskRoute: Hashable { case detail }

/// The compact-width Tasks `NavigationStack` path: `[.detail]` when a detail is open, else empty (the
/// tree, as the stack root, is never in the pushed path). The Decompose slot model stays the source of
/// truth; this path is a derived View projection.
func tasksNavPath(hasDetail: Bool) -> [TaskRoute] {
    hasDetail ? [.detail] : []
}

extension View {
    /// When `title` is non-nil, sets an inline navigation title — used when a Task pane is **pushed** onto
    /// the compact `NavigationStack`, so the native bar (title + back chevron) owns the chrome and the
    /// pane's own `PaneHeader` is suppressed. nil leaves the view untouched (the regular-width split, where
    /// the `PaneHeader` stays).
    @ViewBuilder
    func paneNavigationTitle(_ title: String?) -> some View {
        if let title {
            navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        } else {
            self
        }
    }
}

extension Task {
    /// Stable String identity for SwiftUI list diffing. `Task.id` is an erased value class (opaque
    /// `Any` in Swift), so the Kotlin bridge unwraps it to the underlying UUID String.
    var stableKey: String { BridgeKt.taskKey(task: self) }
}

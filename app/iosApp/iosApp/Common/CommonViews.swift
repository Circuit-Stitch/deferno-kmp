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

/// One node of the Tasks **Item tree** (#227, ADR-0034). The leading ▾/▸ chevron *and* a body tap both
/// toggle a parent's fold; a childless leaf's body is inert. The trailing › opens detail (Task kind only
/// — the other kinds have no detail surface yet). `depth` drives the indent; a collapsed parent shows a
/// `descendantDone/descendantTotal` progress badge (Tasks only); a terminal (Done/Dropped/Archived) row
/// is de-emphasized. Stateless: the handlers take their params from the row, never from observed state.
struct ItemRowView: View {
    let row: ItemRow
    let onToggleExpand: (String, Bool) -> Void
    let onOpenDetail: (String, ItemKind) -> Void

    private var isTask: Bool { BridgeKt.itemKindIsTask(kind: row.item.kind) }

    /// "done/total" for a collapsed Task parent; nil otherwise (recurring kinds carry no subtree counts).
    private var progressBadge: String? {
        guard row.hasChildren, !row.isExpanded,
              let done = row.item.descendantDone, let total = row.item.descendantTotal
        else { return nil }
        return "\(done.intValue)/\(total.intValue)"
    }

    var body: some View {
        HStack(spacing: 12) {
            if row.depth > 0 {
                Spacer().frame(width: CGFloat(row.depth) * 16)
            }
            // Leading chevron — toggles fold; reserved (invisible) on a leaf so titles stay aligned.
            Button {
                if row.hasChildren { onToggleExpand(row.item.id, row.isExpanded) }
            } label: {
                Text(row.isExpanded ? "▾" : "▸")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .frame(width: 16)
                    .opacity(row.hasChildren ? 1 : 0)
            }
            .buttonStyle(.plain)
            .disabled(!row.hasChildren)
            .accessibilityLabel(row.isExpanded ? "Collapse" : "Expand")
            .accessibilityHidden(!row.hasChildren)

            // Title (+ collapsed progress badge). A body tap toggles a parent's fold; a leaf body is inert.
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.title)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let progressBadge {
                    Text(progressBadge)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if row.hasChildren { onToggleExpand(row.item.id, row.isExpanded) } }

            // Trailing › — opens detail; Task kind only (the other kinds have no detail surface yet).
            if isTask {
                Button {
                    onOpenDetail(row.item.id, row.item.kind)
                } label: {
                    Text("›").font(.title3).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open details")
            }
        }
        .frame(minHeight: Layout.rowMinHeight)
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 12)
        .opacity(row.item.isTerminal ? 0.5 : 1.0)
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

/// Whether the Tasks **secondary** pane shows a Task detail or nothing (ADR-0007 tier-2). Since the Item
/// tree became the always-present **primary** pane (#227, ADR-0034), detail is the only secondary slot —
/// so this is just "is a detail open?", with no co-resident precedence to resolve. Mirrors the shared
/// `resolveSecondarySlot` the Android/desktop Views use.
enum SecondarySlot { case detail, none }

func resolveSecondarySlot(hasDetail: Bool) -> SecondarySlot {
    hasDetail ? .detail : .none
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

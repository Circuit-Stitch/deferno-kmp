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

/// A calm, single-line pane header: a title (a VoiceOver heading) with an optional leading **Back**
/// affordance and trailing actions. Used by every pane so placement stays predictable.
struct PaneHeader<Trailing: View>: View {
    let title: String
    let onBack: (() -> Void)?
    let trailing: () -> Trailing

    init(title: String, onBack: (() -> Void)? = nil, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: 8) {
            if let onBack {
                Button("Back", action: onBack)
                    .frame(minHeight: Layout.minTouchTarget)
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
    init(title: String, onBack: (() -> Void)? = nil) {
        self.init(title: title, onBack: onBack) { EmptyView() }
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

/// Which co-resident slot fills the Tasks secondary pane (ADR-0007 tier-2), mirroring the shared
/// `resolveSecondarySlot` precedence the Android/desktop Views use: the most-recently-foregrounded
/// slot when it's actually open, else whichever remains, else nothing. Recency only wins when its own
/// slot is present, so a tree→child drill-in keeps the tree foregrounded rather than snapping to detail.
enum SecondarySlot { case detail, tree, none }

func resolveSecondarySlot(activePane: TaskPane, hasDetail: Bool, hasTree: Bool) -> SecondarySlot {
    if activePane === TaskPane.tree, hasTree { return .tree }
    if activePane === TaskPane.detail, hasDetail { return .detail }
    if hasTree { return .tree }
    if hasDetail { return .detail }
    return .none
}

extension Task {
    /// Stable String identity for SwiftUI list diffing. `Task.id` is an erased value class (opaque
    /// `Any` in Swift), so the Kotlin bridge unwraps it to the underlying UUID String.
    var stableKey: String { BridgeKt.taskKey(task: self) }
}

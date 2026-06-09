import Deferno
import SwiftUI

// The iOS View layer is SwiftUI with **its own design system** (ADR-0003/0004) — not Compose tokens.
// This is the small, touch-first token set the Tasks + Plan Views share (#51, ADR-0010): plain,
// non-shaming working-state labels (matching the Android badge), accessible colour pairs (colour is
// always reinforcement, never the sole signal — WCAG), and large touch targets (design-principles.md).

enum Layout {
    /// Minimum height for a tappable row/control — design-principles.md "≥44–48pt" touch targets.
    static let minTouchTarget: CGFloat = 48
    static let rowMinHeight: CGFloat = 64
    static let gutter: CGFloat = 16
}

extension WorkingState {
    /// The plain, non-shaming label (design-principles.md) — matches the Android `WorkingStateBadge`.
    /// `WorkingState` bridges as an Objective-C class (not a Swift enum), so we match on the singleton
    /// entries by identity rather than a `switch`.
    var label: String {
        if self === WorkingState.open { return "Open" }
        if self === WorkingState.inprogress { return "In progress" }
        if self === WorkingState.inreview { return "In review" }
        if self === WorkingState.done { return "Done" }
        if self === WorkingState.dropped { return "Set aside" }
        return name
    }

    var badgeBackground: Color {
        if self === WorkingState.inprogress { return Color.blue.opacity(0.18) }
        if self === WorkingState.inreview { return Color.purple.opacity(0.18) }
        if self === WorkingState.done { return Color.green.opacity(0.20) }
        return Color(.secondarySystemFill) // Open + Set aside: calm neutral
    }

    var badgeForeground: Color {
        if self === WorkingState.inprogress { return Color(.systemBlue) }
        if self === WorkingState.inreview { return Color(.systemPurple) }
        if self === WorkingState.done { return Color(.systemGreen) }
        if self === WorkingState.dropped { return Color.secondary }
        return Color.primary // Open
    }

    /// The five states, in lifecycle order — drives the detail pane's working-state editor chips.
    static var ordered: [WorkingState] {
        [.open, .inprogress, .inreview, .done, .dropped]
    }
}

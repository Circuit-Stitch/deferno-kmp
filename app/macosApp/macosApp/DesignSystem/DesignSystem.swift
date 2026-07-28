import Deferno
import SwiftUI

// The macOS View layer is SwiftUI with **its own design system** (ADR-0003/0004) — not Compose tokens.
// This is the small token set the Tasks + Plan Views share (#51, ADR-0010): plain, non-shaming
// working-state labels (matching the Android badge), accessible colour pairs drawn from the ACTIVE
// [DefernoColors] palette (colour is always reinforcement, never the sole signal — WCAG), and
// **desktop-density** row metrics — pointer targets, not the touch-first ≥44–48pt the iOS twin uses (a
// mouse hits a tighter row just fine).

enum Layout {
    /// Minimum height for a clickable row/control — desktop pointer target (the iOS twin uses 48pt touch).
    static let minTouchTarget: CGFloat = 28
    /// Minimum row height — compact desktop list density (the iOS twin uses 64pt).
    static let rowMinHeight: CGFloat = 30
    /// Horizontal row gutter — tighter desktop margins (the iOS twin uses 16pt).
    static let gutter: CGFloat = 10
    /// Vertical padding inside a row — compact desktop density (the iOS twin uses 12pt).
    static let rowVerticalPadding: CGFloat = 5
}

extension WorkingState {
    /// The plain, non-shaming label (design-principles.md).
    /// `WorkingState` bridges (via SKIE) as a Swift value-type enum, so we match the cases by value
    /// equality rather than a `switch`.
    var label: String {
        if self == WorkingState.open { return L.string("tasks_menu_open") }
        if self == WorkingState.inProgress { return L.string("common_status_in_progress") }
        if self == WorkingState.inReview { return L.string("common_status_in_review") }
        if self == WorkingState.done { return L.string("calendar_action_done") }
        if self == WorkingState.dropped { return L.string("tasks_set_aside") }
        return name
    }

    /// The five states, in lifecycle order — drives the detail pane's working-state editor chips.
    static var ordered: [WorkingState] {
        [.open, .inProgress, .inReview, .done, .dropped]
    }
}

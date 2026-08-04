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
    /// The drawn diameter of a `CheckDot` — and therefore the leading slot a Plan row reserves, so a
    /// Task's completion dot and a recurring row's kind marker share one title column (#385).
    static let checkDotSize: CGFloat = 24
}

extension WorkingState {
    /// The plain, non-shaming label (design-principles.md) — **the Tasks vocabulary**, and only that.
    /// It is a label for an *item's* progress, so it says "Open" and "Done"; a recurring firing's day
    /// is a different axis (`OccurrenceState`) with its own words, and this is not its fallback. The
    /// calendar chip used to end an `if` chain here and would have printed "Open"/"Done" for the four
    /// readings #402 adds — it now renders a token from `ShellBridgeKt.occurrenceStatusToken` instead,
    /// where a Kotlin `when` makes the mapping exhaustive.
    ///
    /// SKIE bridges the Kotlin enum to a real `@frozen` Swift enum (see the generated
    /// `Model.WorkingState.swift`), so an exhaustive `switch` *is* available here; the `if` chain plus
    /// `return name` is retained deliberately, because `@frozen` means a member added on the Kotlin
    /// side would otherwise break this file's compile only once the Apple CI job runs — long after the
    /// shared change merges. The catch-all is the cost of that, which is exactly why a mapping that
    /// must not silently fall through belongs in Kotlin rather than here.
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

extension Priority {
    /// The bucket's plain word (#375). SKIE bridges the Kotlin enum to a real Swift enum, so this
    /// `switch` is exhaustive — a fourth bucket would fail to compile here rather than fall through to a
    /// raw enum name (the hazard the string-keyed `sortLabel` switches carry).
    var label: String {
        switch self {
        case .fire: return L.string("common_priority_fire")
        case .normal: return L.string("common_priority_normal")
        case .backlog: return L.string("common_priority_backlog")
        }
    }

    /// The three buckets, most-urgent first (the Kotlin declaration order *is* the rank) — drives the
    /// detail pane's priority picker.
    static var ordered: [Priority] { [.fire, .normal, .backlog] }
}

import SwiftUI

/// Which of the reveal drawer's two overlaid layers is reachable by VoiceOver, derived from the
/// settled drawer state (#358).
///
/// `MainShellView` stacks the drawer and the content card in one `ZStack`: closed, the drawer sits
/// *underneath* the full-width content card; open, the content slides aside to reveal it. VoiceOver
/// has no z-order — every element in the tree is reachable regardless of what's visually on top — so
/// each state must explicitly hide the layer the sighted user cannot see. Otherwise a screen-reader
/// user reaches the off-screen nav rows while the drawer is closed (and, since the drawer is the first
/// `ZStack` child, focus lands there *before* the visible content). This maps the drawer state to the
/// two `.accessibilityHidden` gates the View applies, keeping exactly one layer reachable per state.
struct ShellAccessibility {
    /// The settled open/closed state of the drawer — not the mid-drag fraction: VoiceOver focus is
    /// discrete, so it tracks the committed state, not the animation in flight.
    let drawerOpen: Bool

    /// The drawer's nav rows are reachable by VoiceOver only while the drawer is open — hidden when it's
    /// closed, so the off-screen rows can't be focused or activated (#358).
    var drawerHidden: Bool { !drawerOpen }

    /// The content card is hidden from VoiceOver while the drawer is open, so the covered content isn't
    /// reachable underneath the drawer.
    var contentHidden: Bool { drawerOpen }
}

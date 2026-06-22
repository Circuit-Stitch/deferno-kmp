import SwiftUI

/// The Deferno glyph set (iOS twin of core/designsystem `DefernoIcons.kt`). The Compose side hand-builds
/// brand `ImageVector`s; on iOS we map to **SF Symbols** — the native idiom that scales with Dynamic Type
/// and matches the platform — accepting minor drift on the two bespoke glyphs (Sparkle → `sparkles`,
/// Play → `play.fill`). Authoring bespoke `Shape`s is deferred unless that drift is ever rejected.
enum DefernoIcon {
    case sparkle, play, check, plus, chevronRight, chevronLeft, chevronDown
    case menu, moreVert, search, clock, refresh, close
    case mic, waveform
    // Destination + drawer glyphs
    case home, calendar, tasks, inbox, activity, profile, settings
    // Move-mode glyphs
    case undo, indent, outdent, moveUp, moveDown

    var systemName: String {
        switch self {
        case .sparkle: return "sparkles"
        case .play: return "play.fill"
        case .check: return "checkmark"
        case .plus: return "plus"
        case .chevronRight: return "chevron.right"
        case .chevronLeft: return "chevron.left"
        case .chevronDown: return "chevron.down"
        case .menu: return "line.3.horizontal"
        case .moreVert: return "ellipsis"
        case .search: return "magnifyingglass"
        case .clock: return "clock"
        case .refresh: return "arrow.clockwise"
        case .close: return "xmark"
        case .mic: return "mic.fill"
        case .waveform: return "waveform"
        case .home: return "house"
        case .calendar: return "calendar"
        case .tasks: return "list.bullet.indent"
        case .inbox: return "tray"
        case .activity: return "bell"
        case .profile: return "person.crop.circle"
        case .settings: return "gearshape"
        case .undo: return "arrow.uturn.backward"
        case .indent: return "arrow.right.to.line"
        case .outdent: return "arrow.left.to.line"
        case .moveUp: return "arrow.up"
        case .moveDown: return "arrow.down"
        }
    }

    /// The bare symbol image (inherits the surrounding font/foreground).
    var image: Image { Image(systemName: systemName) }

    /// The symbol at an explicit point size — most call sites want a fixed glyph size.
    func image(size: CGFloat, weight: Font.Weight = .regular) -> some View {
        Image(systemName: systemName).font(.system(size: size, weight: weight))
    }
}

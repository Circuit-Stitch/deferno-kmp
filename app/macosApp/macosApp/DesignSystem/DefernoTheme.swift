import Deferno
import SwiftUI

// The iOS design system owns its own touch-first tokens (ADR-0003/0004/0010) — it does NOT import the
// Compose `core/designsystem`. This mirrors that module's two brand palettes (Deferno warm amber/ink,
// Mono grayscale) × light/dark, so the SwiftUI frame re-themes live off the shared
// `RootComponent.themeSettings` exactly like Android's `DefernoTheme` (#72): the family selects the
// palette and `ThemeMode.resolveDark(systemDark:)` resolves light/dark (Auto follows the OS).

/// The brand colour roles the SwiftUI Views read from the environment (the iOS twin of the Compose
/// `MaterialTheme` colour scheme + the `DefernoColors` brand extension).
struct DefernoColors {
    let background: Color
    let surface: Color
    let surfaceVariant: Color
    let surfaceCard: Color
    let onSurface: Color
    let onSurfaceVariant: Color
    let inkMuted: Color
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    /// The deep-amber flame emphasis accent — pinned ★, calendar day dots (brand-only, not an M3 role).
    let amberDeep: Color
    let secondary: Color
    let secondaryContainer: Color
    let tertiary: Color
    let tertiaryContainer: Color
    let error: Color
    let onError: Color
    let errorContainer: Color
    let success: Color
    let successContainer: Color
    let outline: Color
    let outlineVariant: Color
    let lineStrong: Color

    /// Resolve the scheme for a theme family + dark flag (the analogue of `DefernoTheme(palette,darkTheme)`).
    static func resolve(family: ThemeFamily, dark: Bool) -> DefernoColors {
        if family === ThemeFamily.mono { return dark ? .monoDark : .monoLight }
        return dark ? .defernoDark : .defernoLight
    }

    static let defernoLight = DefernoColors(
        background: Color(hex: "E8E0D0"), surface: Color(hex: "E8E0D0"), surfaceVariant: Color(hex: "DED4C0"),
        surfaceCard: Color(hex: "F2ECDC"), onSurface: Color(hex: "2A2620"), onSurfaceVariant: Color(hex: "4A4338"),
        inkMuted: Color(hex: "5C5340"), primary: Color(hex: "C97A1B"), onPrimary: Color(hex: "FFFFFF"),
        primaryContainer: Color(hex: "F4D9B0"), amberDeep: Color(hex: "A05F0E"), secondary: Color(hex: "2F5C8C"),
        secondaryContainer: Color(hex: "CFDDEA"), tertiary: Color(hex: "6B4A8C"), tertiaryContainer: Color(hex: "D9CDE3"),
        error: Color(hex: "B83232"), onError: Color(hex: "FFFFFF"), errorContainer: Color(hex: "F2D2CE"),
        success: Color(hex: "3F7A3F"), successContainer: Color(hex: "D2E3CC"),
        outline: Color(hex: "B8AC92"), outlineVariant: Color(hex: "D4C9B0"), lineStrong: Color(hex: "1F1A12")
    )

    static let defernoDark = DefernoColors(
        background: Color(hex: "2A2620"), surface: Color(hex: "2A2620"), surfaceVariant: Color(hex: "1F1B16"),
        surfaceCard: Color(hex: "3A352D"), onSurface: Color(hex: "F0E2C2"), onSurfaceVariant: Color(hex: "D8CBA8"),
        inkMuted: Color(hex: "C0B496"), primary: Color(hex: "E8B870"), onPrimary: Color(hex: "1F1B14"),
        primaryContainer: Color(hex: "4A3A22"), amberDeep: Color(hex: "F0CE92"), secondary: Color(hex: "99B5D2"),
        secondaryContainer: Color(hex: "2A3850"), tertiary: Color(hex: "BAA5D0"), tertiaryContainer: Color(hex: "3A2D4A"),
        error: Color(hex: "E89A8F"), onError: Color(hex: "1F1B14"), errorContainer: Color(hex: "4A2A26"),
        success: Color(hex: "9CC09C"), successContainer: Color(hex: "2A4029"),
        outline: Color(hex: "5A5247"), outlineVariant: Color(hex: "3A352D"), lineStrong: Color(hex: "0F0C08")
    )

    static let monoLight = DefernoColors(
        background: Color(hex: "DDDDDD"), surface: Color(hex: "DDDDDD"), surfaceVariant: Color(hex: "CCCCCC"),
        surfaceCard: Color(hex: "EEEEEE"), onSurface: Color(hex: "1A1A1A"), onSurfaceVariant: Color(hex: "333333"),
        inkMuted: Color(hex: "555555"), primary: Color(hex: "1A1A1A"), onPrimary: Color(hex: "FFFFFF"),
        primaryContainer: Color(hex: "BBBBBB"), amberDeep: Color(hex: "000000"), secondary: Color(hex: "1A1A1A"),
        secondaryContainer: Color(hex: "BBBBBB"), tertiary: Color(hex: "1A1A1A"), tertiaryContainer: Color(hex: "BBBBBB"),
        error: Color(hex: "1A1A1A"), onError: Color(hex: "FFFFFF"), errorContainer: Color(hex: "BBBBBB"),
        success: Color(hex: "1A1A1A"), successContainer: Color(hex: "BBBBBB"),
        outline: Color(hex: "AAAAAA"), outlineVariant: Color(hex: "BBBBBB"), lineStrong: Color(hex: "222222")
    )

    static let monoDark = DefernoColors(
        background: Color(hex: "222222"), surface: Color(hex: "222222"), surfaceVariant: Color(hex: "1A1A1A"),
        surfaceCard: Color(hex: "2A2A2A"), onSurface: Color(hex: "EEEEEE"), onSurfaceVariant: Color(hex: "CCCCCC"),
        inkMuted: Color(hex: "AAAAAA"), primary: Color(hex: "EEEEEE"), onPrimary: Color(hex: "1A1A1A"),
        primaryContainer: Color(hex: "404040"), amberDeep: Color(hex: "FFFFFF"), secondary: Color(hex: "EEEEEE"),
        secondaryContainer: Color(hex: "404040"), tertiary: Color(hex: "EEEEEE"), tertiaryContainer: Color(hex: "404040"),
        error: Color(hex: "EEEEEE"), onError: Color(hex: "1A1A1A"), errorContainer: Color(hex: "404040"),
        success: Color(hex: "EEEEEE"), successContainer: Color(hex: "404040"),
        outline: Color(hex: "555555"), outlineVariant: Color(hex: "333333"), lineStrong: Color(hex: "000000")
    )
}

private struct DefernoColorsKey: EnvironmentKey {
    static let defaultValue = DefernoColors.defernoLight
}

extension EnvironmentValues {
    /// The active brand palette — read by every Deferno-styled View (`@Environment(\.defernoColors)`).
    var defernoColors: DefernoColors {
        get { self[DefernoColorsKey.self] }
        set { self[DefernoColorsKey.self] = newValue }
    }
}

/// Applies the live theme derived from the Active Account's `UserSettings`: the brand palette goes into
/// the environment, the system tint follows `primary`, and the color scheme follows the resolved mode
/// (so native controls + the system status bar match). Wraps the whole frame at the root.
struct DefernoThemeModifier: ViewModifier {
    let settings: UserSettings
    @Environment(\.colorScheme) private var systemScheme

    func body(content: Content) -> some View {
        let isDark = settings.themeMode.resolveDark(systemDark: systemScheme == .dark)
        let colors = DefernoColors.resolve(family: settings.themeFamily, dark: isDark)
        return content
            .environment(\.defernoColors, colors)
            .tint(colors.primary)
            .preferredColorScheme(isDark ? .dark : .light)
    }
}

extension View {
    /// Apply the Deferno theme derived from `settings` to this subtree (drive it from `themeSettings`).
    func defernoTheme(_ settings: UserSettings) -> some View {
        modifier(DefernoThemeModifier(settings: settings))
    }
}

extension Color {
    /// Build a `Color` from a 6-digit RGB hex string (e.g. `"C97A1B"`), `#` optional.
    init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
        var value: UInt64 = 0
        Scanner(string: cleaned).scanHexInt64(&value)
        let red = Double((value >> 16) & 0xFF) / 255.0
        let green = Double((value >> 8) & 0xFF) / 255.0
        let blue = Double(value & 0xFF) / 255.0
        self.init(.sRGB, red: red, green: green, blue: blue, opacity: 1.0)
    }
}

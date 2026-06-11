import SwiftUI
import XCTest
import Deferno
@testable import iosApp

/// Locks down the `ThemeMode` → scene `preferredColorScheme` mapping (`DefernoTheme.swift`).
/// The load-bearing case is Auto → `nil`: a concrete scheme pins the scene and feeds back into the
/// `\.colorScheme` environment read inside `DefernoThemeModifier`, latching the launch-time
/// appearance so "Follow system" never tracks an OS appearance change.
final class DefernoThemeTests: XCTestCase {

    func testLightModePinsTheSceneLight() {
        XCTAssertEqual(ThemeMode.light.preferredColorScheme, .light)
    }

    func testDarkModePinsTheSceneDark() {
        XCTAssertEqual(ThemeMode.dark.preferredColorScheme, .dark)
    }

    /// The regression: Auto must not pin a concrete scheme — `nil` keeps the scene on the OS appearance.
    func testAutoModeDoesNotPinTheScene() {
        XCTAssertNil(ThemeMode.auto_.preferredColorScheme)
    }
}

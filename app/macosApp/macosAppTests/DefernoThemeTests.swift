import SwiftUI
import XCTest
import Deferno
@testable import macosApp

/// Locks down the `ThemeMode` → scene `preferredColorScheme` mapping (`DefernoTheme.swift`).
/// The load-bearing case is Auto → `nil`: a concrete scheme pins the scene and feeds back into the
/// `\.colorScheme` environment read inside `DefernoThemeModifier`, latching the launch-time
/// appearance so "Follow system" never tracks an OS appearance change.
///
/// The macOS port of `iosAppTests/DefernoThemeTests.swift`. ADR-0028 sanctions per-platform View-body
/// divergence, but this mapping is *not* a place to diverge — macOS shipped the exact bug iOS fixed in
/// cacc8bce because nothing on this side held the line (there was no macOS CI at all until now).
final class DefernoThemeTests: XCTestCase {

    func testLightModePinsTheSceneLight() {
        XCTAssertEqual(ThemeMode.light.preferredColorScheme, .light)
    }

    func testDarkModePinsTheSceneDark() {
        XCTAssertEqual(ThemeMode.dark.preferredColorScheme, .dark)
    }

    /// The regression: Auto must not pin a concrete scheme — `nil` keeps the scene on the OS appearance.
    func testAutoModeDoesNotPinTheScene() {
        XCTAssertNil(ThemeMode.auto.preferredColorScheme)
    }
}

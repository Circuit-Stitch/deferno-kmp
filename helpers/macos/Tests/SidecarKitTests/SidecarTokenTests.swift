import XCTest
import Darwin
@testable import SidecarKit

final class SidecarTokenTests: XCTestCase {

    // MARK: constant-time compare

    func testConstantTimeEqualsMatchesValueEquality() {
        XCTAssertTrue(SidecarToken.constantTimeEquals("abc", "abc"))
        XCTAssertTrue(SidecarToken.constantTimeEquals("", ""))
        XCTAssertFalse(SidecarToken.constantTimeEquals("abc", "abd"))
        XCTAssertFalse(SidecarToken.constantTimeEquals("abc", "ab"))   // length mismatch
        XCTAssertFalse(SidecarToken.constantTimeEquals("abc", "abcd")) // length mismatch
        XCTAssertFalse(SidecarToken.constantTimeEquals("secret", ""))
        // Multi-byte UTF-8 handled by comparing the byte sequences.
        XCTAssertTrue(SidecarToken.constantTimeEquals("tökén", "tökén"))
        XCTAssertFalse(SidecarToken.constantTimeEquals("tökén", "token"))
    }

    // MARK: resolution precedence

    func testExplicitTokenWins() throws {
        let token = try SidecarToken.resolve(explicit: "x", environment: [SidecarToken.envVar: "y"])
        XCTAssertEqual(token, "x")
    }

    func testEnvVarUsedWhenNoExplicit() throws {
        let token = try SidecarToken.resolve(environment: [SidecarToken.envVar: "from-env"])
        XCTAssertEqual(token, "from-env")
    }

    func testMissingThrows() {
        XCTAssertThrowsError(try SidecarToken.resolve(environment: [:]))
    }

    // MARK: token-file 0600 enforcement (peer-auth leg 2 posture)

    func testTokenFileMustBeOwnerOnly() throws {
        let path = NSTemporaryDirectory() + "deferno-token-\(getpid()).secret"
        try "file-token\n".write(toFile: path, atomically: true, encoding: .utf8)
        defer { unlink(path) }

        // Group/other-readable → refused.
        chmod(path, 0o644)
        XCTAssertThrowsError(try SidecarToken.resolve(file: path, environment: [:])) { error in
            guard case SidecarToken.ResolutionError.insecureFile = error else {
                return XCTFail("expected insecureFile, got \(error)")
            }
        }

        // Owner-only → accepted, trimmed.
        chmod(path, 0o600)
        XCTAssertEqual(try SidecarToken.resolve(file: path, environment: [:]), "file-token")
    }

    func testEmptyTokenFileThrows() throws {
        let path = NSTemporaryDirectory() + "deferno-token-empty-\(getpid()).secret"
        try "   \n".write(toFile: path, atomically: true, encoding: .utf8)
        chmod(path, 0o600)
        defer { unlink(path) }
        XCTAssertThrowsError(try SidecarToken.resolve(file: path, environment: [:])) { error in
            guard case SidecarToken.ResolutionError.empty = error else {
                return XCTFail("expected empty, got \(error)")
            }
        }
    }
}

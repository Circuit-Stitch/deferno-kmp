import XCTest
@testable import SidecarKit

/// Independent verification of the helper's wire layer against the **golden fixtures** in
/// `contracts/sidecar/fixtures/` — the *same* canonical frames the JVM `SidecarFrameSerializationTest`
/// decodes. Reading the repo's golden files directly (no copy) means the helper's codec is proven
/// against the one cross-language contract, so the wire can't drift between the two implementations.
final class SidecarWireTests: XCTestCase {

    /// The repo root, derived from this source file's location (…/helpers/macos/Tests/SidecarKitTests/X).
    private static let repoRoot: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // SidecarKitTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // macos
        .deletingLastPathComponent() // helpers
        .deletingLastPathComponent() // <repo root>

    private func fixture(_ name: String) throws -> Data {
        let url = Self.repoRoot
            .appendingPathComponent("contracts/sidecar/fixtures")
            .appendingPathComponent(name)
        return try Data(contentsOf: url)
    }

    // MARK: golden fixtures decode to the documented frames

    func testGoldenHelloDecodes() throws {
        guard case .hello(let token, let version) = try decodeFrameBody(fixture("hello.json")) else {
            return XCTFail("expected hello")
        }
        XCTAssertEqual(token, "EXAMPLE-IN-BAND-TOKEN")
        XCTAssertEqual(version, 1)
    }

    func testGoldenWelcomeDecodes() throws {
        guard case .welcome(let version, let caps) = try decodeFrameBody(fixture("welcome.json")) else {
            return XCTFail("expected welcome")
        }
        XCTAssertEqual(version, 1)
        XCTAssertEqual(Set(caps), [SidecarCapabilities.permissions, SidecarCapabilities.speechTranscribe])
    }

    func testGoldenResponsePayloadDecodesToPermissionStatus() throws {
        guard case .response(let id, let result) = try decodeFrameBody(fixture("query-permission-response.json")) else {
            return XCTFail("expected response")
        }
        XCTAssertEqual(id, 1)
        XCTAssertEqual(result?.string("capability"), "speech")
        XCTAssertEqual(result?.string("status"), "granted")
    }

    func testGoldenStreamPayloadDecodesToTranscript() throws {
        guard case .streamData(let id, let event) = try decodeFrameBody(fixture("transcript-stream-data.json")) else {
            return XCTFail("expected stream_data")
        }
        XCTAssertEqual(id, 2)
        XCTAssertEqual(event.string("type"), "partial")
        XCTAssertEqual(event.string("text"), "hello wor")
    }

    func testGoldenPushDecodes() throws {
        guard case .push(let topic, let payload) = try decodeFrameBody(fixture("push-permission-changed.json")) else {
            return XCTFail("expected push")
        }
        XCTAssertEqual(topic, SidecarTopics.permissionChanged)
        XCTAssertEqual(payload.string("capability"), "mic")
        XCTAssertEqual(payload.string("status"), "denied")
    }

    func testGoldenFailureDecodes() throws {
        guard case .failure(let id, let error) = try decodeFrameBody(fixture("failure.json")) else {
            return XCTFail("expected failure")
        }
        XCTAssertEqual(id, 1)
        XCTAssertEqual(error.code, .unknownMethod)
    }

    func testGoldenPostNotificationRequestDecodes() throws {
        guard case .request(let id, let method, let params) = try decodeFrameBody(fixture("post-notification-request.json")) else {
            return XCTFail("expected request")
        }
        XCTAssertEqual(id, 3)
        XCTAssertEqual(method, SidecarMethods.postNotification)
        XCTAssertEqual(
            PostNotificationRequest(params: params),
            PostNotificationRequest(title: "Deferno", body: "\"Pack for the trip\" is due soon")
        )
    }

    func testPostNotificationParseRejectsAMissingOrEmptyTitle() {
        XCTAssertNil(PostNotificationRequest(params: nil))
        XCTAssertNil(PostNotificationRequest(params: .object(["body": .string("no title")])))
        XCTAssertNil(PostNotificationRequest(params: .object(["title": .string("")])))
    }

    func testGoldenRequestPermissionRequestDecodes() throws {
        guard case .request(let id, let method, let params) = try decodeFrameBody(fixture("request-permission-request.json")) else {
            return XCTFail("expected request")
        }
        XCTAssertEqual(id, 4)
        XCTAssertEqual(method, SidecarMethods.requestPermission)
        XCTAssertEqual(params?.string("capability"), SidecarPermissionCapability.microphone)
    }

    func testGoldenStatusItemFixturesDecode() throws {
        guard case .request(let id, let method, let params) = try decodeFrameBody(fixture("set-status-item-request.json")) else {
            return XCTFail("expected request")
        }
        XCTAssertEqual(id, 4)
        XCTAssertEqual(method, SidecarMethods.setStatusItem)
        XCTAssertEqual(SetStatusItemRequest(params: params), SetStatusItemRequest(visible: true))

        guard case .push(let topic, _) = try decodeFrameBody(fixture("status-item-clicked-push.json")) else {
            return XCTFail("expected push")
        }
        XCTAssertEqual(topic, SidecarTopics.statusItemClicked)
    }

    func testGoldenHotkeyFixturesDecode() throws {
        guard case .request(let id, let method, let params) = try decodeFrameBody(fixture("register-hotkey-request.json")) else {
            return XCTFail("expected request")
        }
        XCTAssertEqual(id, 5)
        XCTAssertEqual(method, SidecarMethods.registerHotkey)
        XCTAssertEqual(
            RegisterHotkeyRequest(params: params),
            RegisterHotkeyRequest(id: 1, key: "d", modifiers: [.command, .shift])
        )

        guard case .push(let topic, let payload) = try decodeFrameBody(fixture("hotkey-fired-push.json")) else {
            return XCTFail("expected push")
        }
        XCTAssertEqual(topic, SidecarTopics.hotkeyFired)
        guard case .object(let o) = payload, case .int(let firedId)? = o["id"] else {
            return XCTFail("expected an id payload")
        }
        XCTAssertEqual(firedId, 1)
    }

    func testRegisterHotkeyParseEnforcesTheContract() {
        // Unknown key, empty modifiers, unknown modifier, missing id — all invalid_params at parse.
        XCTAssertNil(RegisterHotkeyRequest(params: .object(["id": .int(1), "key": .string("münzwurf"), "modifiers": .array([.string("command")])])))
        XCTAssertNil(RegisterHotkeyRequest(params: .object(["id": .int(1), "key": .string("d"), "modifiers": .array([])])))
        XCTAssertNil(RegisterHotkeyRequest(params: .object(["id": .int(1), "key": .string("d"), "modifiers": .array([.string("hyper")])])))
        XCTAssertNil(RegisterHotkeyRequest(params: .object(["key": .string("d"), "modifiers": .array([.string("command")])])))
    }

    func testHotkeyKeyTableCoversTheDocumentedSet() {
        // The contract names a–z, 0–9, the named keys, and f1–f12 (26 + 10 + 4 + 12) — the same set the
        // JVM SidecarHotkeyKeys validates, so invalid keys fail identically on both implementations.
        XCTAssertEqual(HotkeyKeyTable.keyCodes.count, 52)
        for key in ["a", "z", "0", "9", "space", "return", "escape", "tab", "f1", "f12"] {
            XCTAssertNotNil(HotkeyKeyTable.keyCodes[key], key)
        }
    }

    // MARK: encode shape matches the contract (discriminator + omitted defaults)

    func testEncodeTagsTypeDiscriminator() throws {
        let json = try String(decoding: encodeFrameBody(.request(id: 1, method: "queryPermission", params: nil)), as: UTF8.self)
        XCTAssertTrue(json.contains("\"type\":\"request\""), json)
    }

    func testEncodeOmitsNullAndDefaultFields() throws {
        let request = try String(decoding: encodeFrameBody(.request(id: 1, method: "m", params: nil)), as: UTF8.self)
        XCTAssertFalse(request.contains("params"), request)

        let welcome = try String(decoding: encodeFrameBody(.welcome(protocolVersion: 1, capabilities: [])), as: UTF8.self)
        XCTAssertFalse(welcome.contains("capabilities"), welcome)

        // A connection-level failure omits id; a correlated one includes it.
        let connFailure = try String(decoding: encodeFrameBody(.failure(id: nil, error: SidecarError(.unauthenticated, "x"))), as: UTF8.self)
        XCTAssertFalse(connFailure.contains("\"id\""), connFailure)
    }

    func testUnknownErrorCodeCoercesToUnknown() throws {
        let data = Data(#"{"type":"failure","error":{"code":"teleport","message":"???"}}"#.utf8)
        guard case .failure(_, let error) = try decodeFrameBody(data) else { return XCTFail("expected failure") }
        XCTAssertEqual(error.code, .unknown)
    }

    func testToleratesUnknownKeysFromANewerClient() throws {
        let data = Data(#"{"type":"welcome","protocolVersion":1,"futureField":{"x":true}}"#.utf8)
        guard case .welcome(let v, _) = try decodeFrameBody(data) else { return XCTFail("expected welcome") }
        XCTAssertEqual(v, 1)
    }

    // MARK: payload helpers round-trip

    func testPermissionStatusJSONRoundTrips() throws {
        let status = PermissionStatus(capability: "speech", status: .granted)
        let data = try encodeFrameBody(.response(id: 7, result: status.json))
        guard case .response(let id, let result) = try decodeFrameBody(data) else { return XCTFail() }
        XCTAssertEqual(id, 7)
        XCTAssertEqual(result?.string("capability"), "speech")
        XCTAssertEqual(result?.string("status"), "granted")
    }

    func testTranscriptEventVariantsRoundTrip() throws {
        let events: [TranscriptEvent] = [.partial(text: "a"), .final(text: "b"), .failure(reason: "capture")]
        for event in events {
            let data = try encodeFrameBody(.streamData(id: 3, event: event.json))
            guard case .streamData(_, let decoded) = try decodeFrameBody(data) else { return XCTFail() }
            XCTAssertEqual(decoded.string("type"), {
                switch event { case .partial: return "partial"; case .final: return "final"; case .failure: return "failure" }
            }())
        }
    }

    // MARK: privacy — payload-bearing frames redact contents in description

    func testDescriptionRedactsPayloads() {
        let frame = SidecarFrame.streamData(id: 1, event: .object(["text": .string("SECRET")]))
        XCTAssertFalse(frame.description.contains("SECRET"), frame.description)
        let push = SidecarFrame.push(topic: "t", payload: .object(["text": .string("SECRET")]))
        XCTAssertFalse(push.description.contains("SECRET"), push.description)
    }
}

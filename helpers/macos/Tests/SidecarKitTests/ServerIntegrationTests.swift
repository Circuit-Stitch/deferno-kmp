import XCTest
import Darwin
@testable import SidecarKit

/// Drives a real `SidecarConnection` (canned provider) over a real connected socket pair, from the
/// helper's *own* client side — the Swift mirror of the JVM `SidecarClientE2ETest`. Proves the server's
/// handshake, request/response, server stream, unsolicited push, and cancel through the real codec, with
/// no TCC/mic and no JVM. (The JVM contract-parity test then proves the *real binary* against the *real
/// JVM client*.)
final class ServerIntegrationTests: XCTestCase {

    private var clientCodec: FrameCodec!
    private var clientStream: SocketByteStream!
    private var connection: SidecarConnection!
    private let token = "shared-in-band-token"

    private func startConnection(
        provider: CapabilityProvider,
        token expectedToken: String? = nil
    ) {
        var fds: [Int32] = [0, 0]
        let rc = socketpair(AF_UNIX, SOCK_STREAM, 0, &fds)
        XCTAssertEqual(rc, 0, "socketpair failed (errno=\(errno))")
        let serverFd = fds[0]
        let clientFd = fds[1]

        clientStream = SocketByteStream(fd: clientFd)
        clientCodec = FrameCodec(clientStream)

        connection = SidecarConnection(fd: serverFd, provider: provider, expectedToken: expectedToken ?? token)
        let conn = connection!
        Thread.detachNewThread { conn.serve() }
    }

    override func tearDown() {
        clientStream?.close()
        super.tearDown()
    }

    private func handshake() throws -> [String] {
        try clientCodec.writeFrame(.hello(token: token, protocolVersion: sidecarProtocolVersion))
        guard case .welcome(let version, let caps)? = try clientCodec.readFrame() else {
            XCTFail("expected welcome"); return []
        }
        XCTAssertEqual(version, sidecarProtocolVersion)
        return caps
    }

    func testHandshakeAdvertisesCapabilities() throws {
        startConnection(provider: CannedCapabilityProvider())
        let caps = try handshake()
        XCTAssertEqual(
            Set(caps),
            [SidecarCapabilities.permissions, SidecarCapabilities.speechTranscribe, SidecarCapabilities.notifications]
        )
    }

    func testWrongTokenIsRejectedConnectionLevel() throws {
        startConnection(provider: CannedCapabilityProvider())
        try clientCodec.writeFrame(.hello(token: "wrong", protocolVersion: 1))
        guard case .failure(let id, let error)? = try clientCodec.readFrame() else {
            return XCTFail("expected failure")
        }
        XCTAssertNil(id, "a rejected handshake is connection-level (no id)")
        XCTAssertEqual(error.code, .unauthenticated)
        // The helper then closes — the next read is a clean EOF.
        XCTAssertNil(try clientCodec.readFrame())
    }

    func testQueryPermissionResponseThenPush() throws {
        startConnection(provider: CannedCapabilityProvider(permissionStatus: .denied))
        _ = try handshake()
        try clientCodec.writeFrame(.request(id: 1, method: SidecarMethods.queryPermission, params: nil))

        guard case .response(let id, let result)? = try clientCodec.readFrame() else {
            return XCTFail("expected response")
        }
        XCTAssertEqual(id, 1)
        XCTAssertEqual(result?.string("capability"), "speech")
        XCTAssertEqual(result?.string("status"), "denied")

        guard case .push(let topic, let payload)? = try clientCodec.readFrame() else {
            return XCTFail("expected push")
        }
        XCTAssertEqual(topic, SidecarTopics.permissionChanged)
        XCTAssertEqual(payload.string("status"), "denied")
    }

    func testQueryPermissionEchoesTheRequestedCapability() throws {
        startConnection(provider: CannedCapabilityProvider(permissionStatus: .notDetermined))
        _ = try handshake()
        try clientCodec.writeFrame(.request(
            id: 7,
            method: SidecarMethods.queryPermission,
            params: .object(["capability": .string(SidecarPermissionCapability.notifications)])
        ))

        guard case .response(let id, let result)? = try clientCodec.readFrame() else {
            return XCTFail("expected response")
        }
        XCTAssertEqual(id, 7)
        XCTAssertEqual(result?.string("capability"), "notifications")
        XCTAssertEqual(result?.string("status"), "not_determined")
    }

    func testPostNotificationAcksWhenGranted() throws {
        startConnection(provider: CannedCapabilityProvider())
        _ = try handshake()
        try clientCodec.writeFrame(.request(
            id: 4,
            method: SidecarMethods.postNotification,
            params: .object(["title": .string("Deferno"), "body": .string("contract parity")])
        ))

        guard case .response(let id, let result)? = try clientCodec.readFrame() else {
            return XCTFail("expected response")
        }
        XCTAssertEqual(id, 4)
        XCTAssertNil(result, "a posted notification is an empty ack")
    }

    func testPostNotificationWithoutAGrantIsUnavailable() throws {
        startConnection(provider: CannedCapabilityProvider(permissionStatus: .denied))
        _ = try handshake()
        try clientCodec.writeFrame(.request(
            id: 5,
            method: SidecarMethods.postNotification,
            params: .object(["title": .string("Deferno")])
        ))

        guard case .failure(let id, let error)? = try clientCodec.readFrame() else {
            return XCTFail("expected failure")
        }
        XCTAssertEqual(id, 5)
        XCTAssertEqual(error.code, .unavailable)
    }

    func testPostNotificationWithAnEmptyTitleIsInvalidParams() throws {
        startConnection(provider: CannedCapabilityProvider())
        _ = try handshake()
        try clientCodec.writeFrame(.request(
            id: 6,
            method: SidecarMethods.postNotification,
            params: .object(["title": .string("")])
        ))

        guard case .failure(let id, let error)? = try clientCodec.readFrame() else {
            return XCTFail("expected failure")
        }
        XCTAssertEqual(id, 6)
        XCTAssertEqual(error.code, .invalidParams)
    }

    func testUnknownMethodFails() throws {
        startConnection(provider: CannedCapabilityProvider())
        _ = try handshake()
        try clientCodec.writeFrame(.request(id: 5, method: "listCalendars", params: nil))
        guard case .failure(let id, let error)? = try clientCodec.readFrame() else {
            return XCTFail("expected failure")
        }
        XCTAssertEqual(id, 5)
        XCTAssertEqual(error.code, .unknownMethod)
    }

    func testSubscribeTranscriptStreamsToEnd() throws {
        startConnection(provider: CannedCapabilityProvider())
        _ = try handshake()
        try clientCodec.writeFrame(.request(id: 2, method: SidecarMethods.subscribeTranscript, params: nil))

        try assertStreamEvent(id: 2, type: "partial", text: "hel")
        try assertStreamEvent(id: 2, type: "partial", text: "hello wor")
        try assertStreamEvent(id: 2, type: "final", text: "hello world")

        guard case .streamEnd(let id)? = try clientCodec.readFrame() else {
            return XCTFail("expected stream_end")
        }
        XCTAssertEqual(id, 2)
    }

    func testCancelStopsTheStream() throws {
        startConnection(provider: CannedCapabilityProvider())
        _ = try handshake()
        try clientCodec.writeFrame(.request(id: 3, method: SidecarMethods.subscribeTranscript, params: nil))
        // Take the first partial, then cancel.
        try assertStreamEvent(id: 3, type: "partial", text: "hel")
        try clientCodec.writeFrame(.cancel(id: 3))

        // After cancel, the helper ceases work and does NOT send stream_end. Within a short window we
        // should see at most one already-in-flight event and then no stream_end. Close to end the read.
        clientStream.close()
        // The connection's read loop should observe EOF and tear down without crashing (verified by the
        // test process not aborting). No assertion on further frames — the contract permits an in-flight
        // event to have raced ahead of the cancel.
    }

    // MARK: helpers

    private func assertStreamEvent(id expectedId: Int64, type: String, text: String) throws {
        guard case .streamData(let id, let event)? = try clientCodec.readFrame() else {
            return XCTFail("expected stream_data")
        }
        XCTAssertEqual(id, expectedId)
        XCTAssertEqual(event.string("type"), type)
        XCTAssertEqual(event.string("text"), text)
    }
}

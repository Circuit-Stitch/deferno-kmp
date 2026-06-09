import XCTest
@testable import SidecarKit

/// An in-memory `ByteStream` for exercising the framing without a socket: writes append to a buffer that
/// reads drain. `chunkLimit` caps each `readChunk` so we can force the codec to reassemble a frame across
/// **short reads** (the property a length-prefixed framer must hold). Mirrors the JVM `FrameCodecTest`.
private final class LoopbackStream: ByteStream {
    private var buffer = Data()
    private var cursor = 0
    private var closed = false
    let chunkLimit: Int

    init(chunkLimit: Int = .max) { self.chunkLimit = chunkLimit }

    func readChunk(maxBytes: Int) throws -> Data {
        let available = buffer.count - cursor
        if available <= 0 { return Data() } // EOF / nothing buffered
        let n = min(min(maxBytes, chunkLimit), available)
        let slice = buffer.subdata(in: cursor..<(cursor + n))
        cursor += n
        return slice
    }

    func write(_ data: Data) throws {
        if closed { throw SidecarProtocolError("write after close") }
        buffer.append(data)
    }

    func close() { closed = true }
}

final class FrameCodecTests: XCTestCase {

    private let sampleFrames: [SidecarFrame] = [
        .hello(token: "tok", protocolVersion: 1),
        .welcome(protocolVersion: 1, capabilities: [SidecarCapabilities.permissions, SidecarCapabilities.speechTranscribe]),
        .request(id: 1, method: "queryPermission", params: nil),
        .response(id: 1, result: PermissionStatus(capability: "speech", status: .granted).json),
        .streamData(id: 2, event: TranscriptEvent.partial(text: "hel").json),
        .streamEnd(id: 2),
        .cancel(id: 2),
        .push(topic: SidecarTopics.permissionChanged, payload: PermissionStatus(capability: "mic", status: .denied).json),
        .failure(id: 3, error: SidecarError(.unknownMethod, "no such method: x")),
        .failure(id: nil, error: SidecarError(.unauthenticated, "bad token")),
    ]

    func testRoundTripsEachFrameKind() throws {
        for frame in sampleFrames {
            let stream = LoopbackStream()
            let codec = FrameCodec(stream)
            try codec.writeFrame(frame)
            let decoded = try codec.readFrame()
            XCTAssertEqual(decoded, frame, "round-trip mismatch for \(frame.type)")
        }
    }

    func testReassemblesAcrossShortReads() throws {
        let stream = LoopbackStream(chunkLimit: 1) // one byte per read — maximal fragmentation
        let codec = FrameCodec(stream)
        try codec.writeFrame(.response(id: 9, result: .object(["k": .string("value")])))
        let decoded = try codec.readFrame()
        guard case .response(let id, _) = decoded else { return XCTFail("expected response") }
        XCTAssertEqual(id, 9)
    }

    func testWritesAndReadsBackToBack() throws {
        let stream = LoopbackStream(chunkLimit: 3)
        let codec = FrameCodec(stream)
        for frame in sampleFrames { try codec.writeFrame(frame) }
        for expected in sampleFrames {
            XCTAssertEqual(try codec.readFrame(), expected)
        }
    }

    func testCleanEofBetweenFramesReturnsNil() throws {
        let stream = LoopbackStream()
        let codec = FrameCodec(stream)
        XCTAssertNil(try codec.readFrame()) // nothing buffered → clean EOF
    }

    func testTruncatedFrameThrows() throws {
        let stream = LoopbackStream()
        // A length prefix promising 100 bytes, with a short body → truncation.
        try stream.write(Data([0, 0, 0, 100]))
        try stream.write(Data("partial".utf8))
        let codec = FrameCodec(stream)
        XCTAssertThrowsError(try codec.readFrame())
    }

    func testRejectsOversizeInboundLength() throws {
        let stream = LoopbackStream()
        try stream.write(Data([0x7f, 0xff, 0xff, 0xff])) // ~2 GiB, far over the 1 MiB cap
        let codec = FrameCodec(stream)
        XCTAssertThrowsError(try codec.readFrame()) { error in
            XCTAssertTrue(error is SidecarProtocolError)
        }
    }

    func testRejectsZeroLength() throws {
        let stream = LoopbackStream()
        try stream.write(Data([0, 0, 0, 0]))
        let codec = FrameCodec(stream)
        XCTAssertThrowsError(try codec.readFrame())
    }

    func testRejectsOversizeOutboundFrame() throws {
        let codec = FrameCodec(LoopbackStream(), maxFrameBytes: 16)
        XCTAssertThrowsError(try codec.writeFrame(.push(topic: "t", payload: .string(String(repeating: "x", count: 100)))))
    }
}

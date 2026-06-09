import Foundation

/// A protocol-level wire failure (oversize/unframeable/malformed frame). Its message is **metadata
/// only** — it never echoes the offending body, which may carry Transcript text (ADR-0009).
public struct SidecarProtocolError: Error, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { "SidecarProtocolError(\(message))" }
}

// MARK: - Frame ⇄ JSON

private let frameEncoder = JSONEncoder()
private let frameDecoder = JSONDecoder()

/// Encode a frame to its UTF-8 JSON body (no length prefix). Tagged with `"type"`, absent fields omitted.
public func encodeFrameBody(_ frame: SidecarFrame) throws -> Data {
    try frameEncoder.encode(frame.jsonObject)
}

/// Decode one UTF-8 JSON body to a frame. Tolerant of unknown keys; an unrecognized error code coerces
/// to `.unknown`. Never echoes the body on failure.
public func decodeFrameBody(_ data: Data) throws -> SidecarFrame {
    let value: JSONValue
    do {
        value = try frameDecoder.decode(JSONValue.self, from: data)
    } catch {
        throw SidecarProtocolError("malformed inbound frame")
    }
    guard case .object(let o) = value, let type = o["type"]?.asString else {
        throw SidecarProtocolError("frame missing \"type\"")
    }
    switch type {
    case "hello":
        return .hello(token: o["token"]?.asString ?? "", protocolVersion: o["protocolVersion"]?.asInt ?? 0)
    case "welcome":
        let caps = (o["capabilities"]?.asArray ?? []).compactMap { $0.asString }
        return .welcome(protocolVersion: o["protocolVersion"]?.asInt ?? 0, capabilities: caps)
    case "request":
        guard let id = o["id"]?.asInt64, let method = o["method"]?.asString else {
            throw SidecarProtocolError("request missing id/method")
        }
        return .request(id: id, method: method, params: o["params"])
    case "response":
        guard let id = o["id"]?.asInt64 else { throw SidecarProtocolError("response missing id") }
        return .response(id: id, result: o["result"])
    case "stream_data":
        guard let id = o["id"]?.asInt64, let event = o["event"] else {
            throw SidecarProtocolError("stream_data missing id/event")
        }
        return .streamData(id: id, event: event)
    case "stream_end":
        guard let id = o["id"]?.asInt64 else { throw SidecarProtocolError("stream_end missing id") }
        return .streamEnd(id: id)
    case "cancel":
        guard let id = o["id"]?.asInt64 else { throw SidecarProtocolError("cancel missing id") }
        return .cancel(id: id)
    case "push":
        guard let topic = o["topic"]?.asString, let payload = o["payload"] else {
            throw SidecarProtocolError("push missing topic/payload")
        }
        return .push(topic: topic, payload: payload)
    case "failure":
        guard case .object(let err)? = o["error"], let codeStr = err["code"]?.asString else {
            throw SidecarProtocolError("failure missing error.code")
        }
        let code = SidecarErrorCode(rawValue: codeStr) ?? .unknown
        let error = SidecarError(code, err["message"]?.asString ?? "", details: err["details"])
        return .failure(id: o["id"]?.asInt64, error: error)
    default:
        throw SidecarProtocolError("unknown frame type")
    }
}

// MARK: - Length-prefixed framing over a byte stream

/// A bidirectional byte stream the codec sits above — deliberately raw bytes, not a socket, so the same
/// codec serves AF_UNIX today and any future transport (ADR-0025).
public protocol ByteStream: AnyObject {
    /// Read up to `maxBytes`. Returns an **empty** `Data` at clean end-of-stream (peer closed).
    func readChunk(maxBytes: Int) throws -> Data
    /// Write all of `data` (the implementation loops over short writes).
    func write(_ data: Data) throws
    /// Release the stream.
    func close()
}

/// The Sidecar framing: each frame is a **4-byte big-endian length prefix + UTF-8 JSON body**, capped
/// at `maxFrameBytes`. The exact mirror of `core/sidecar`'s `FrameCodec`.
public final class FrameCodec {
    private let stream: ByteStream
    private let maxFrameBytes: Int

    public init(_ stream: ByteStream, maxFrameBytes: Int = sidecarMaxFrameBytes) {
        self.stream = stream
        self.maxFrameBytes = maxFrameBytes
    }

    public func writeFrame(_ frame: SidecarFrame) throws {
        let body = try encodeFrameBody(frame)
        if body.count > maxFrameBytes {
            throw SidecarProtocolError("outbound \(frame.type) too large: \(body.count) > \(maxFrameBytes)")
        }
        let n = UInt32(body.count)
        var out = Data(capacity: 4 + body.count)
        out.append(UInt8((n >> 24) & 0xff))
        out.append(UInt8((n >> 16) & 0xff))
        out.append(UInt8((n >> 8) & 0xff))
        out.append(UInt8(n & 0xff))
        out.append(body)
        try stream.write(out)
    }

    /// Read exactly one frame. Returns `nil` at a **clean** end-of-stream between frames (peer closed);
    /// an EOF partway through a frame is a truncation error.
    public func readFrame() throws -> SidecarFrame? {
        guard let header = try readFully(4, atFrameBoundary: true) else { return nil }
        let length = (Int(header[0]) << 24) | (Int(header[1]) << 16) | (Int(header[2]) << 8) | Int(header[3])
        if length <= 0 || length > maxFrameBytes {
            throw SidecarProtocolError("inbound frame length out of range: \(length)")
        }
        guard let body = try readFully(length, atFrameBoundary: false) else {
            throw SidecarProtocolError("stream ended mid-frame")
        }
        return try decodeFrameBody(body)
    }

    private func readFully(_ n: Int, atFrameBoundary: Bool) throws -> Data? {
        var buffer = Data(capacity: n)
        while buffer.count < n {
            let chunk = try stream.readChunk(maxBytes: n - buffer.count)
            if chunk.isEmpty {
                if atFrameBoundary && buffer.isEmpty { return nil }
                throw SidecarProtocolError("stream ended mid-frame")
            }
            buffer.append(chunk)
        }
        return buffer
    }
}

// MARK: - JSONValue accessors

extension JSONValue {
    var asString: String? { if case .string(let s) = self { return s } else { return nil } }
    var asArray: [JSONValue]? { if case .array(let a) = self { return a } else { return nil } }

    /// A 64-bit integer if this is an integral number (an `.int`, or a `.double` with no fraction).
    var asInt64: Int64? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d.rounded() == d && abs(d) < 9.0e18: return Int64(d)
        default: return nil
        }
    }

    /// `Int` convenience for protocol-version fields.
    var asInt: Int? { asInt64.map { Int($0) } }
}

import Foundation

/// One message on the Sidecar protocol — a JSON object tagged with a `"type"` discriminator, framed
/// length-prefixed over the socket. The Swift mirror of `core/sidecar`'s sealed `SidecarFrame`.
///
/// Inbound (client → helper): `hello`, `request`, `cancel`.
/// Outbound (helper → client): `welcome`, `response`, `stream_data`, `stream_end`, `push`, `failure`.
///
/// Correlation `id`s are positive integers chosen by the client; the helper echoes them back.
public enum SidecarFrame: Equatable {
    case hello(token: String, protocolVersion: Int)
    case welcome(protocolVersion: Int, capabilities: [String])
    case request(id: Int64, method: String, params: JSONValue?)
    case response(id: Int64, result: JSONValue?)
    case streamData(id: Int64, event: JSONValue)
    case streamEnd(id: Int64)
    case cancel(id: Int64)
    case push(topic: String, payload: JSONValue)
    case failure(id: Int64?, error: SidecarError)
}

extension SidecarFrame {
    /// The `"type"` discriminator string (part of the contract).
    var type: String {
        switch self {
        case .hello: return "hello"
        case .welcome: return "welcome"
        case .request: return "request"
        case .response: return "response"
        case .streamData: return "stream_data"
        case .streamEnd: return "stream_end"
        case .cancel: return "cancel"
        case .push: return "push"
        case .failure: return "failure"
        }
    }

    /// The frame as an opaque object, **omitting null/default fields** to match the contract
    /// (`explicitNulls=false` / `encodeDefaults=false` on the JVM side): a null `params`/`result` and an
    /// empty `capabilities`/absent `id` are left off the wire.
    var jsonObject: JSONValue {
        var o: [String: JSONValue] = ["type": .string(type)]
        switch self {
        case .hello(let token, let protocolVersion):
            o["token"] = .string(token)
            o["protocolVersion"] = .int(Int64(protocolVersion))
        case .welcome(let protocolVersion, let capabilities):
            o["protocolVersion"] = .int(Int64(protocolVersion))
            if !capabilities.isEmpty { o["capabilities"] = .array(capabilities.map { .string($0) }) }
        case .request(let id, let method, let params):
            o["id"] = .int(id)
            o["method"] = .string(method)
            if let params { o["params"] = params }
        case .response(let id, let result):
            o["id"] = .int(id)
            if let result { o["result"] = result }
        case .streamData(let id, let event):
            o["id"] = .int(id)
            o["event"] = event
        case .streamEnd(let id):
            o["id"] = .int(id)
        case .cancel(let id):
            o["id"] = .int(id)
        case .push(let topic, let payload):
            o["topic"] = .string(topic)
            o["payload"] = payload
        case .failure(let id, let error):
            if let id { o["id"] = .int(id) }
            var err: [String: JSONValue] = [
                "code": .string(error.code.rawValue),
                "message": .string(error.message),
            ]
            if let details = error.details { err["details"] = details }
            o["error"] = .object(err)
        }
        return .object(o)
    }
}

extension SidecarFrame: CustomStringConvertible {
    /// **Privacy (ADR-0009):** payload-bearing frames redact their contents — only metadata (type, id,
    /// method, topic, error code) appears, so a stray log can never leak Transcript text.
    public var description: String {
        switch self {
        case .hello(_, let v): return "hello(token=<redacted>, protocolVersion=\(v))"
        case .welcome(let v, let caps): return "welcome(protocolVersion=\(v), capabilities=\(caps))"
        case .request(let id, let m, _): return "request(id=\(id), method=\(m), params=<redacted>)"
        case .response(let id, _): return "response(id=\(id), result=<redacted>)"
        case .streamData(let id, _): return "stream_data(id=\(id), event=<redacted>)"
        case .streamEnd(let id): return "stream_end(id=\(id))"
        case .cancel(let id): return "cancel(id=\(id))"
        case .push(let topic, _): return "push(topic=\(topic), payload=<redacted>)"
        case .failure(let id, let e): return "failure(id=\(id.map(String.init) ?? "nil"), code=\(e.code.rawValue))"
        }
    }
}

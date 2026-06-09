import Foundation

/// A minimal, `Codable` JSON value — the Swift counterpart of the opaque `JsonElement` payloads the
/// JVM `core/sidecar` client carries (`Request.params`, `Response.result`, `StreamData.event`,
/// `Push.payload`, `error.details`). The Sidecar protocol treats these as opaque, so the helper builds
/// outbound payloads (a `PermissionStatus`, a `TranscriptEvent`) and decodes inbound `params` through
/// this one type rather than fighting `Codable`'s tagged-enum representation.
///
/// **Privacy (ADR-0009):** a value can carry privacy-critical Transcript text. Its `description` is
/// deliberately **not** derived from the contents — never log a `JSONValue` payload.
public enum JSONValue: Equatable {
    case null
    case bool(Bool)
    /// JSON has one number type; the helper models integral ids as `int` and other numbers as `double`.
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
}

extension JSONValue: Codable {
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let i = try? container.decode(Int64.self) {
            self = .int(i)
        } else if let d = try? container.decode(Double.self) {
            self = .double(d)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else if let a = try? container.decode([JSONValue].self) {
            self = .array(a)
        } else if let o = try? container.decode([String: JSONValue].self) {
            self = .object(o)
        } else {
            throw DecodingError.dataCorrupted(
                .init(codingPath: decoder.codingPath, debugDescription: "unrepresentable JSON value")
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let b): try container.encode(b)
        case .int(let i): try container.encode(i)
        case .double(let d): try container.encode(d)
        case .string(let s): try container.encode(s)
        case .array(let a): try container.encode(a)
        case .object(let o): try container.encode(o)
        }
    }
}

public extension JSONValue {
    /// The string at `key` if this is an object holding a string there — used for opaque `params`
    /// introspection (e.g. an optional `capability` argument) without committing to a typed schema.
    func string(_ key: String) -> String? {
        guard case .object(let o) = self, case .string(let s)? = o[key] else { return nil }
        return s
    }
}

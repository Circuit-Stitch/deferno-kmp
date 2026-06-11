import Foundation

/// A capability's permission state on the wire — the `queryPermission` result and the
/// `permissionChanged` push payload. Mirrors `core/sidecar`'s `PermissionStatusWire`; the JVM side
/// decodes tolerantly (an unrecognized `status` coerces to `unknown`).
public enum PermissionStatusValue: String {
    case granted
    case denied
    case notDetermined = "not_determined"
    case restricted
    case unknown
}

/// `{ "capability": string, "status": ... }`. Carries no private content.
public struct PermissionStatus: Equatable {
    public let capability: String
    public let status: PermissionStatusValue

    public init(capability: String, status: PermissionStatusValue) {
        self.capability = capability
        self.status = status
    }

    /// Encode to the opaque payload the wire carries.
    public var json: JSONValue {
        .object([
            "capability": .string(capability),
            "status": .string(status.rawValue),
        ])
    }
}

/// The `postNotification` params (#123): a user-visible OS notification to deliver. Mirrors
/// `core/sidecar`'s `PostNotificationWire`. A missing/empty `title` is `invalid_params` — modelled as
/// a failed parse (`init?`) so the connection rejects it before any provider runs.
///
/// **Privacy (ADR-0009):** the title/body are user content (a task name, a due date) — like every
/// payload, they are never logged; only metadata may surface in diagnostics.
public struct PostNotificationRequest: Equatable {
    public let title: String
    public let body: String?

    public init(title: String, body: String? = nil) {
        self.title = title
        self.body = body
    }

    /// Parse from the opaque wire `params`; nil when malformed (no object, or a missing/empty title).
    public init?(params: JSONValue?) {
        guard let title = params?.string("title"), !title.isEmpty else { return nil }
        self.init(title: title, body: params?.string("body"))
    }
}

/// The `setStatusItem` params (#125): show or hide the helper's menu-bar status item. Mirrors
/// `core/sidecar`'s `SetStatusItemWire`; a missing `visible` is a failed parse → `invalid_params`.
public struct SetStatusItemRequest: Equatable {
    public let visible: Bool

    public init(visible: Bool) {
        self.visible = visible
    }

    public init?(params: JSONValue?) {
        guard case .object(let o)? = params, case .bool(let visible)? = o["visible"] else { return nil }
        self.init(visible: visible)
    }
}

/// The `registerHotkey` params (#125). Mirrors `core/sidecar`'s `RegisterHotkeyWire`: a client-chosen
/// `id` (echoed in every `hotkeyFired` push; re-registering replaces it), a `HotkeyKeyTable` key name,
/// and a non-empty modifier subset of command/option/control/shift. Any violation is a failed parse →
/// `invalid_params`, identical to the JVM stub's validation.
public struct RegisterHotkeyRequest: Equatable {
    public let id: Int64
    public let key: String
    public let modifiers: Set<HotkeyModifier>

    public init(id: Int64, key: String, modifiers: Set<HotkeyModifier>) {
        self.id = id
        self.key = key
        self.modifiers = modifiers
    }

    public init?(params: JSONValue?) {
        guard
            case .object(let o)? = params,
            case .int(let id)? = o["id"],
            case .string(let key)? = o["key"], HotkeyKeyTable.keyCodes[key] != nil,
            case .array(let rawModifiers)? = o["modifiers"], !rawModifiers.isEmpty
        else { return nil }
        var modifiers = Set<HotkeyModifier>()
        for raw in rawModifiers {
            guard case .string(let name) = raw, let modifier = HotkeyModifier(rawValue: name) else { return nil }
            modifiers.insert(modifier)
        }
        self.init(id: id, key: key, modifiers: modifiers)
    }
}

/// The `unregisterHotkey` params (#125). Mirrors `core/sidecar`'s `UnregisterHotkeyWire`.
public struct UnregisterHotkeyRequest: Equatable {
    public let id: Int64

    public init(id: Int64) {
        self.id = id
    }

    public init?(params: JSONValue?) {
        guard case .object(let o)? = params, case .int(let id)? = o["id"] else { return nil }
        self.init(id: id)
    }
}

/// A hotkey modifier on the wire (#125). Mirrors `core/sidecar`'s `HotkeyModifier`.
public enum HotkeyModifier: String, CaseIterable {
    case command
    case option
    case control
    case shift
}

/// The wire form of a dictation event, carried in `stream_data.event` on the `subscribeTranscript`
/// stream. Mirrors `core/sidecar`'s `TranscriptWire`.
///
/// **Privacy (ADR-0009/0018):** `partial`/`final` text is privacy-critical — the recognized audio never
/// crosses this seam (the helper emits text, not PCM), and the text is never logged. `reason` is a
/// **non-PII** cause.
public enum TranscriptEvent: Equatable {
    case partial(text: String)
    case final(text: String)
    case failure(reason: String)

    /// Encode to the opaque payload the wire carries.
    public var json: JSONValue {
        switch self {
        case .partial(let text):
            return .object(["type": .string("partial"), "text": .string(text)])
        case .final(let text):
            return .object(["type": .string("final"), "text": .string(text)])
        case .failure(let reason):
            return .object(["type": .string("failure"), "reason": .string(reason)])
        }
    }
}

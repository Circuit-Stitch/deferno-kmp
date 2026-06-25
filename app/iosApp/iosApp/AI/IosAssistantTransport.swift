import Deferno
import Foundation

/// The iOS SSE turn-stream transport (#282, ADR-0040): implements the shared Kotlin `NativeAssistantTransport`
/// over a raw `URLSession` byte stream. NSURLSession streams Server-Sent Events without the buffering the Ktor
/// Darwin engine imposes, so the chat reply arrives token-by-token (ADR-0040's reason for a native seam).
/// Kotlin owns the request (URL, Bearer PAT, JSON body) and the parsing; this only POSTs, reads SSE frames
/// line-by-line, and hands each frame's `(event, data)` back, then signals completion/failure exactly once.
final class IosAssistantTransport: NativeAssistantTransport {

    func stream(
        url: String,
        authToken: String?,
        body: String,
        onEvent: @escaping (String, String) -> Void,
        onDone: @escaping () -> Void,
        onError: @escaping (String) -> Void
    ) -> NativeStreamHandle {
        // Fire onDone/onError exactly once even if the stream both ends and errors during teardown.
        let once = FireOnce()
        // `_Concurrency.Task` — bare `Task` resolves to the exported Kotlin `Deferno.Task` domain model.
        let task = _Concurrency.Task {
            guard let endpoint = URL(string: url) else { once.run { onError("invalid-url") }; return }
            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.httpBody = body.data(using: .utf8)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            if let authToken { request.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }

            Self.debugLog("POST \(url) (auth=\(authToken == nil ? "none" : "yes"))")
            do {
                let (bytes, response) = try await URLSession.shared.bytes(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? -1
                Self.debugLog("status=\(status)")
                if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                    once.run { onError("http-\(http.statusCode)") }
                    return
                }

                // Accumulate one SSE frame (its `event:` type + joined `data:` lines) until a blank line.
                var eventType = ""
                var dataLines: [String] = []
                func dispatch() {
                    guard !eventType.isEmpty || !dataLines.isEmpty else { return }
                    let data = dataLines.joined(separator: "\n")
                    Self.debugLog("frame event='\(eventType)' dataLen=\(data.count)")
                    onEvent(eventType, data)
                    eventType = ""
                    dataLines = []
                }

                for try await rawLine in bytes.lines {
                    // The server uses CRLF line endings, so `AsyncLineSequence` (splitting on \n) leaves a
                    // trailing \r — strip it so a frame-separating blank line reads as truly empty.
                    let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
                    if line.isEmpty {
                        dispatch() // blank line = end of frame
                    } else if line.hasPrefix(":") {
                        continue // SSE comment / heartbeat
                    } else if line.hasPrefix("event:") {
                        // A new event begins — flush the previous frame first, so frame separation doesn't
                        // depend solely on the blank line being delivered (belt-and-suspenders vs CRLF quirks).
                        dispatch()
                        eventType = Self.fieldValue(line, after: "event:")
                    } else if line.hasPrefix("data:") {
                        dataLines.append(Self.fieldValue(line, after: "data:"))
                    }
                    // Other SSE fields (id:, retry:) are not used by this client.
                }
                dispatch() // flush a trailing frame with no terminating blank line
                Self.debugLog("stream closed")
                once.run { onDone() }
            } catch {
                // A cancellation (the collector went away — onCancelTurn / awaitClose) is not an error; the
                // Kotlin flow is already closing, so just stand down quietly.
                Self.debugLog("catch cancelled=\(_Concurrency.Task.isCancelled) err=\(error)")
                if _Concurrency.Task.isCancelled || error is CancellationError {
                    once.run { onDone() }
                } else {
                    once.run { onError("transport-error") }
                }
            }
        }
        return SSEHandle(task: task)
    }

    /// Debug-only stream diagnostics (compiled out of Release) — non-PII (status, event name + length, never
    /// the reply content or the token). via `NSLog` (unbuffered → os_log), capturable with
    /// `xcrun simctl spawn booted log show --predicate 'process == "Deferno"'`. Earned its keep finding the
    /// CRLF frame-split bug.
    private static func debugLog(_ message: @autoclosure () -> String) {
        #if DEBUG
        NSLog("[AssistantSSE] %@", message())
        #endif
    }

    /// The value of an SSE field line, dropping the field name and the single optional leading space (spec).
    private static func fieldValue(_ line: String, after prefix: String) -> String {
        var value = Substring(line.dropFirst(prefix.count))
        if value.hasPrefix(" ") { value = value.dropFirst() }
        return String(value)
    }
}

/// Cancels the in-flight streaming task (wired to the Kotlin Flow's `awaitClose`).
private final class SSEHandle: NativeStreamHandle {
    private let task: _Concurrency.Task<Void, Never>
    init(task: _Concurrency.Task<Void, Never>) { self.task = task }
    func cancel() { task.cancel() }
}

/// Serializes the one-shot terminal callback so the Kotlin flow completes exactly once.
private final class FireOnce {
    private let lock = NSLock()
    private var done = false

    func run(_ block: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { block() }
    }
}

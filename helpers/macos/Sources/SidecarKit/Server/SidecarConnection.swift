import Foundation

/// Serves one accepted client connection (ADR-0024). Runs its read loop on a dedicated thread (blocking
/// framed reads); all outbound frames funnel through one serial `writeQueue`, so replies from the read
/// thread, transcript callbacks, and permission pushes can never interleave on the wire. The peer's uid
/// was already checked at `accept` (`UnixSocketListener.peerIsCurrentUser`); this completes peer-auth by
/// validating the in-band token before any capability is served.
public final class SidecarConnection {

    private let codec: FrameCodec
    private let stream: ByteStream
    private let provider: CapabilityProvider
    private let expectedToken: String
    private let writeQueue: DispatchQueue
    private let onClosed: (() -> Void)?

    /// Active server streams by request id; guarded by `streamsLock` (mutated on the read thread,
    /// inspected from transcript-callback threads).
    private final class StreamState {
        var handle: TranscriptHandle?
        var cancelledByClient = false
    }
    private var streams: [Int64: StreamState] = [:]
    private let streamsLock = NSLock()
    private var closed = false
    private let closeLock = NSLock()

    public init(fd: Int32, provider: CapabilityProvider, expectedToken: String, onClosed: (() -> Void)? = nil) {
        self.stream = SocketByteStream(fd: fd)
        self.codec = FrameCodec(stream)
        self.provider = provider
        self.expectedToken = expectedToken
        self.writeQueue = DispatchQueue(label: "com.circuitstitch.deferno.sidecar.write.\(fd)")
        self.onClosed = onClosed
        provider.permissionChangeSink = { [weak self] status in
            self?.send(.push(topic: SidecarTopics.permissionChanged, payload: status.json))
        }
    }

    /// Run the connection to completion (blocking). Intended to be called on its own thread.
    public func serve() {
        defer { teardown() }
        do {
            // Handshake leg 2 (in-band token). Leg 1 (path 0600) is the client's; leg 3 (uid) was done
            // at accept. A clean EOF before hello = client gave up; just close.
            guard let first = try codec.readFrame() else { return }
            guard case .hello(let token, _) = first, SidecarToken.constantTimeEquals(token, expectedToken) else {
                send(.failure(id: nil, error: SidecarError(.unauthenticated, "invalid sidecar token")))
                drainWritesThenNothing()
                return
            }
            send(.welcome(protocolVersion: sidecarProtocolVersion, capabilities: provider.capabilities))

            while let frame = try codec.readFrame() {
                handle(frame)
            }
        } catch {
            // Protocol error or connection lost — metadata only, never the body (ADR-0009).
            // (Intentionally no payload logging.)
        }
    }

    // MARK: dispatch

    private func handle(_ frame: SidecarFrame) {
        switch frame {
        case .request(let id, let method, let params):
            switch method {
            case SidecarMethods.queryPermission:
                let result = provider.queryPermission(params: params)
                send(.response(id: id, result: result.response.json))
                if let push = result.push {
                    send(.push(topic: SidecarTopics.permissionChanged, payload: push.json))
                }
            case SidecarMethods.subscribeTranscript:
                startTranscript(id: id)
            case SidecarMethods.postNotification:
                guard let request = PostNotificationRequest(params: params) else {
                    send(.failure(id: id, error: SidecarError(.invalidParams, "postNotification requires a non-empty title")))
                    return
                }
                provider.postNotification(request) { [weak self] error in
                    self?.ack(id: id, error: error)
                }
            case SidecarMethods.setStatusItem:
                guard let request = SetStatusItemRequest(params: params) else {
                    send(.failure(id: id, error: SidecarError(.invalidParams, "setStatusItem requires visible")))
                    return
                }
                provider.setStatusItem(
                    visible: request.visible,
                    onClick: { [weak self] in
                        self?.send(.push(topic: SidecarTopics.statusItemClicked, payload: .object([:])))
                    },
                    completion: { [weak self] error in
                        self?.ack(id: id, error: error)
                    }
                )
            case SidecarMethods.registerHotkey:
                guard let request = RegisterHotkeyRequest(params: params) else {
                    send(.failure(
                        id: id,
                        error: SidecarError(.invalidParams, "registerHotkey requires a known key and a non-empty modifier set")
                    ))
                    return
                }
                provider.registerHotkey(
                    request,
                    onFire: { [weak self] in
                        self?.send(.push(topic: SidecarTopics.hotkeyFired, payload: .object(["id": .int(request.id)])))
                    },
                    completion: { [weak self] error in
                        self?.ack(id: id, error: error)
                    }
                )
            case SidecarMethods.unregisterHotkey:
                guard let request = UnregisterHotkeyRequest(params: params) else {
                    send(.failure(id: id, error: SidecarError(.invalidParams, "unregisterHotkey requires an id")))
                    return
                }
                provider.unregisterHotkey(id: request.id) { [weak self] error in
                    self?.ack(id: id, error: error)
                }
            default:
                send(.failure(id: id, error: SidecarError(.unknownMethod, "no such method: \(method)")))
            }
        case .cancel(let id):
            cancelTranscript(id: id)
        // Client→server frames a helper never receives; a well-behaved client won't send them.
        case .hello, .welcome, .response, .streamData, .streamEnd, .push, .failure:
            break
        }
    }

    private func startTranscript(id: Int64) {
        let state = StreamState()
        streamsLock.lock()
        streams[id] = state
        streamsLock.unlock()

        let handle = provider.startTranscript(
            onEvent: { [weak self] event in
                self?.send(.streamData(id: id, event: event.json))
            },
            onEnd: { [weak self] in
                self?.endTranscript(id: id)
            }
        )

        // The read loop is single-threaded, so a cancel for this id cannot have been processed yet — but
        // guard anyway: if it somehow was, cancel the freshly-made handle immediately.
        streamsLock.lock()
        if let state = streams[id] {
            state.handle = handle
            if state.cancelledByClient { handle.cancel() }
        } else {
            streamsLock.unlock()
            handle.cancel()
            return
        }
        streamsLock.unlock()
    }

    /// Natural completion (final/failure): send `stream_end` unless the client already cancelled.
    private func endTranscript(id: Int64) {
        streamsLock.lock()
        let state = streams.removeValue(forKey: id)
        streamsLock.unlock()
        guard let state, !state.cancelledByClient else { return }
        send(.streamEnd(id: id))
    }

    /// Client `cancel`: stop the work (release the mic) and stay silent (no `stream_end`).
    private func cancelTranscript(id: Int64) {
        streamsLock.lock()
        let state = streams.removeValue(forKey: id)
        state?.cancelledByClient = true
        streamsLock.unlock()
        state?.handle?.cancel()
    }

    // MARK: outbound

    /// The empty-ack-or-failure reply shape shared by every provider-completed unary method.
    private func ack(id: Int64, error: SidecarError?) {
        if let error {
            send(.failure(id: id, error: error))
        } else {
            send(.response(id: id, result: nil))
        }
    }

    private func send(_ frame: SidecarFrame) {
        writeQueue.async { [weak self] in
            guard let self else { return }
            do {
                try self.codec.writeFrame(frame)
            } catch {
                self.close() // a failed write means the peer is gone
            }
        }
    }

    /// Block until queued writes (e.g. a rejection `failure`) have flushed before returning to close.
    private func drainWritesThenNothing() {
        writeQueue.sync {}
    }

    // MARK: teardown

    private func teardown() {
        streamsLock.lock()
        let active = Array(streams.values)
        streams.removeAll()
        streamsLock.unlock()
        active.forEach { $0.handle?.cancel() } // release the mic on any open stream
        provider.connectionClosed() // remove the status item + unregister this client's hotkeys (#125)
        // Close the fd FIRST, then drain: a write parked in a blocking Darwin.write (peer stopped reading
        // but hasn't closed) is unblocked by the close (EBADF) so the write queue drains instead of the
        // read thread deadlocking on writeQueue.sync. (The handshake-rejection path drains before this
        // teardown runs, so its rejection frame is already flushed.)
        close()
        drainWritesThenNothing()
        onClosed?()
    }

    private func close() {
        closeLock.lock()
        defer { closeLock.unlock() }
        guard !closed else { return }
        closed = true
        stream.close()
    }
}

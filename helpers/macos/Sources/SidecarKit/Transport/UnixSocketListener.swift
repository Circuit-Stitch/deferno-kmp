import Foundation
import Darwin
import launch

/// The AF_UNIX listener for the Sidecar helper, in two activation modes (ADR-0024):
///
/// - **launchd socket activation** (production): launchd owns the socket, binds + `listen()`s it
///   owner-only, and hands the inherited fd to the helper via `launch_activate_socket()`. The JVM client
///   stays launchd-agnostic — it just dials the well-known path launchd bound.
/// - **self-bind** (`--listen <path>`, dev/test/CI): the helper binds the path itself and `chmod`s it
///   `0600`, exactly modelling what launchd does — this is what lets the contract-parity integration
///   test drive the real binary with no launchd.
///
/// Either way the result is one or more listening fds; `acceptLoop` blocks accepting peers and, after the
/// **server-half peer-credential check** (`getpeereid` uid == current user — the helper's leg of
/// peer-auth the JVM can't do portably), hands each connection fd to `onConnection`.
public enum UnixSocketListener {

    public enum ListenerError: Error, CustomStringConvertible {
        case launchd(name: String, errno: Int32)
        case noSocketsActivated(name: String)
        case syscall(String, Int32)
        case pathTooLong(String)

        public var description: String {
            switch self {
            case .launchd(let name, let e): return "launch_activate_socket(\(name)) failed (errno=\(e))"
            case .noSocketsActivated(let name): return "launchd activated no sockets named \(name)"
            case .syscall(let call, let e): return "\(call) failed (errno=\(e))"
            case .pathTooLong(let p): return "socket path too long for sockaddr_un: \(p)"
            }
        }
    }

    /// Retrieve the launchd-passed, already-bound-and-listening socket fd(s) for the named entry in the
    /// LaunchAgent plist's `Sockets` dictionary. The caller owns the returned C array → it is copied and
    /// `free`d here.
    public static func activatedSockets(named name: String) throws -> [Int32] {
        var fds: UnsafeMutablePointer<Int32>? = nil
        var count = 0 // size_t imports as Int
        let err = withUnsafeMutablePointer(to: &fds) { p -> Int32 in
            p.withMemoryRebound(to: UnsafeMutablePointer<Int32>.self, capacity: 1) { rp in
                launch_activate_socket(name, rp, &count)
            }
        }
        guard err == 0 else { throw ListenerError.launchd(name: name, errno: err) }
        guard let fds else { return [] }
        defer { free(fds) }
        return Array(UnsafeBufferPointer(start: fds, count: count))
    }

    /// Bind a fresh AF_UNIX stream socket at `path`, lock it to `0600`, and `listen()`. Models what
    /// launchd does so the self-bind dev/test path is identical to production from the client's view.
    public static func bind(path: String) throws -> Int32 {
        signal(SIGPIPE, SIG_IGN) // a write to a vanished peer must not kill the process

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        // Hoist the sun_path capacity (104 on macOS) into a local BEFORE taking &addr.sun_path below —
        // reading addr.sun_path for its size *inside* that closure is an overlapping-access error.
        let capacity = MemoryLayout.size(ofValue: addr.sun_path)
        guard path.utf8.count < capacity else { throw ListenerError.pathTooLong(path) }

        // Best-effort: make sure the parent dir exists and clear any stale socket file at the path.
        try? FileManager.default.createDirectory(
            at: URL(fileURLWithPath: path).deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        unlink(path)

        let listenFd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard listenFd >= 0 else { throw ListenerError.syscall("socket", errno) }

        withUnsafeMutablePointer(to: &addr.sun_path) { tuple in
            tuple.withMemoryRebound(to: CChar.self, capacity: capacity) { cptr in
                _ = strlcpy(cptr, path, capacity)
            }
        }

        let bindRC = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listenFd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard bindRC == 0 else {
            let e = errno; Darwin.close(listenFd); throw ListenerError.syscall("bind", e)
        }
        // Owner-only — the client's PosixPeerTrust refuses a group/other-accessible socket (ADR-0009).
        guard chmod(path, 0o600) == 0 else {
            let e = errno; Darwin.close(listenFd); unlink(path); throw ListenerError.syscall("chmod", e)
        }
        guard listen(listenFd, 16) == 0 else {
            let e = errno; Darwin.close(listenFd); unlink(path); throw ListenerError.syscall("listen", e)
        }
        return listenFd
    }

    /// Block accepting peers on `listenFd`. For each connection, enforce the server-half uid check
    /// (`getpeereid` == current user) and hand the authorized fd to `onConnection`; a non-matching peer
    /// is closed and rejected. Returns when `listenFd` is closed (clean shutdown).
    public static func acceptLoop(listenFd: Int32, onConnection: @escaping (Int32) -> Void) {
        while true {
            let clientFd = accept(listenFd, nil, nil)
            if clientFd < 0 {
                if errno == EINTR { continue }
                break // listen fd closed / shutting down
            }
            if peerIsCurrentUser(clientFd) {
                onConnection(clientFd)
            } else {
                // Reject a different-uid peer outright (it must not even reach the token handshake).
                Darwin.close(clientFd)
            }
        }
    }

    /// The server-half peer-credential check: the connecting peer's effective uid must equal this
    /// process's uid. Defends the privacy-critical Transcript stream against another local user.
    public static func peerIsCurrentUser(_ fd: Int32) -> Bool {
        var uid: uid_t = 0
        var gid: gid_t = 0
        guard getpeereid(fd, &uid, &gid) == 0 else { return false }
        return uid == getuid()
    }
}

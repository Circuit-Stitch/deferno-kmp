import Foundation
import Darwin

/// A blocking `ByteStream` over a connected socket file descriptor. Read happens on the connection's
/// dedicated read thread; writes are funnelled through the connection's serial queue — POSIX permits
/// concurrent `read`/`write` on the same socket fd, so the two never need a shared lock.
///
/// Closing the fd from another thread is what unblocks a thread parked in `read()` (used for teardown
/// on SIGTERM / connection drop) — the same trick the JVM stub helper uses.
public final class SocketByteStream: ByteStream {
    private let fd: Int32
    private let closedFlag = NSLock()
    private var isClosed = false

    public init(fd: Int32) {
        self.fd = fd
    }

    public func readChunk(maxBytes: Int) throws -> Data {
        guard maxBytes > 0 else { return Data() }
        var buffer = [UInt8](repeating: 0, count: maxBytes)
        while true {
            let n = buffer.withUnsafeMutableBytes { raw in
                Darwin.read(fd, raw.baseAddress, maxBytes)
            }
            if n > 0 { return Data(buffer[0..<n]) }
            if n == 0 { return Data() } // clean EOF (peer closed)
            // n < 0
            if errno == EINTR { continue } // interrupted before any byte — retry
            if errno == ECONNRESET || errno == EBADF || errno == ENOTCONN { return Data() } // treat as EOF
            throw SidecarProtocolError("socket read failed (errno=\(errno))")
        }
    }

    public func write(_ data: Data) throws {
        guard !data.isEmpty else { return }
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress else { return }
            var total = 0
            let count = raw.count
            while total < count {
                let n = Darwin.write(fd, base + total, count - total)
                if n > 0 { total += n; continue }
                if n < 0 && errno == EINTR { continue }
                throw SidecarProtocolError("socket write failed (errno=\(errno))")
            }
        }
    }

    public func close() {
        closedFlag.lock()
        defer { closedFlag.unlock() }
        guard !isClosed else { return }
        isClosed = true
        Darwin.close(fd)
    }
}

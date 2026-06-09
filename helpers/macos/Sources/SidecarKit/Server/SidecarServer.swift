import Foundation

/// Owns the listening socket(s) and spawns a `SidecarConnection` per authorized peer. A fresh
/// `CapabilityProvider` is minted for each connection (via `providerFactory`) so per-connection push
/// routing stays isolated; process-wide resource exclusivity (the mic) lives inside the provider.
public final class SidecarServer {

    private let expectedToken: String
    private let providerFactory: () -> CapabilityProvider

    public init(token: String, providerFactory: @escaping () -> CapabilityProvider) {
        self.expectedToken = token
        self.providerFactory = providerFactory
    }

    /// Start accepting on each listening fd, each on its own thread. Returns immediately; keep the
    /// process alive with `dispatchMain()`.
    public func start(listenFds: [Int32]) {
        for fd in listenFds {
            let thread = Thread { [weak self] in
                UnixSocketListener.acceptLoop(listenFd: fd) { clientFd in
                    self?.handleConnection(clientFd)
                }
            }
            thread.name = "deferno.sidecar.accept.\(fd)"
            thread.start()
        }
    }

    private func handleConnection(_ fd: Int32) {
        let connection = SidecarConnection(
            fd: fd,
            provider: providerFactory(),
            expectedToken: expectedToken
        )
        let thread = Thread { connection.serve() }
        thread.name = "deferno.sidecar.conn.\(fd)"
        thread.start()
    }
}

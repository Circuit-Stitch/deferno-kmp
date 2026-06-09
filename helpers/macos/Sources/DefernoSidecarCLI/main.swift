import Foundation
import Darwin
import SidecarKit

// The Deferno macOS Sidecar helper entry point (ADR-0024, #121). launchd activates this on first connect
// and hands it the inherited, owner-only socket; it serves the Sidecar protocol to the JVM `core/sidecar`
// client. A `--listen <path>` self-bind mode (no launchd) drives the same code from the contract-parity
// integration test and dev runs.
//
// Diagnostics go to stderr and are **metadata only** — never a frame payload or Transcript text (ADR-0009).

func log(_ message: String) {
    FileHandle.standardError.write(Data("deferno-sidecar: \(message)\n".utf8))
}

func fail(_ message: String) -> Never {
    log(message)
    exit(1)
}

// MARK: argument parsing

struct Options {
    var listenPath: String?
    var socketName = "Sidecar"
    var token: String?
    var tokenFile: String?
    var contractFixtures = false
    var fixturePermission: PermissionStatusValue = .granted
}

func parse(_ argv: [String]) -> Options {
    var options = Options()
    var i = 0
    func value(_ flag: String) -> String {
        i += 1
        guard i < argv.count else { fail("\(flag) requires a value") }
        return argv[i]
    }
    while i < argv.count {
        let arg = argv[i]
        switch arg {
        case "--version":
            print("deferno-sidecar protocol v\(sidecarProtocolVersion)")
            exit(0)
        case "--help", "-h":
            print("""
            deferno-sidecar — Deferno macOS Sidecar helper (ADR-0024)
              (default)                      launchd socket-activation mode
              --listen <path>                self-bind an AF_UNIX socket at <path> (dev/test)
              --socket-name <name>           launchd Sockets entry name (default: Sidecar)
              --token <value>                in-band auth token (or $\(SidecarToken.envVar))
              --token-file <path>            read the token from a 0600 file
              --contract-fixtures            serve the canned stub-parity responses (no TCC/mic)
              --fixture-permission <status>  canned permission status (granted|denied|…)
              --version | --help
            """)
            exit(0)
        case "--listen": options.listenPath = value(arg)
        case "--socket-name": options.socketName = value(arg)
        case "--token": options.token = value(arg)
        case "--token-file": options.tokenFile = value(arg)
        case "--contract-fixtures": options.contractFixtures = true
        case "--fixture-permission":
            options.fixturePermission = PermissionStatusValue(rawValue: value(arg)) ?? .granted
        default:
            fail("unknown argument: \(arg)")
        }
        i += 1
    }
    return options
}

// MARK: signal handling — graceful SIGTERM/SIGINT (launchd stop), retained sources

var signalSources: [DispatchSourceSignal] = []

/// In self-bind mode the helper owns the socket file and must remove it on exit; in launchd mode launchd
/// owns it, so this stays nil and is never unlinked here.
var selfBindSocketPath: String?

func installSignalHandlers() {
    for sig in [SIGTERM, SIGINT] {
        signal(sig, SIG_IGN) // required first, else the default disposition kills the process
        let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
        source.setEventHandler {
            log("received signal \(sig); shutting down")
            if let path = selfBindSocketPath { unlink(path) } // don't orphan the self-bound socket file
            exit(0)
        }
        source.resume()
        signalSources.append(source) // retain — a deallocated source cancels and never fires
    }
}

// MARK: main

// A write to a client that has vanished must never kill the helper. Set this unconditionally at startup
// — in launchd socket-activation mode `UnixSocketListener.bind()` (which also sets it) is never called.
signal(SIGPIPE, SIG_IGN)

let options = parse(Array(CommandLine.arguments.dropFirst()))

let token: String
do {
    token = try SidecarToken.resolve(explicit: options.token, file: options.tokenFile)
} catch {
    fail("\(error)")
}

let providerFactory: () -> CapabilityProvider = options.contractFixtures
    ? { CannedCapabilityProvider(permissionStatus: options.fixturePermission) }
    : { RealCapabilityProvider() }

let server = SidecarServer(token: token, providerFactory: providerFactory)

let listenFds: [Int32]
if let path = options.listenPath {
    do {
        listenFds = [try UnixSocketListener.bind(path: path)]
        selfBindSocketPath = path // remove it on shutdown (we own it; launchd owns the production one)
        log("listening (self-bind) at \(path)")
    } catch {
        fail("bind failed: \(error)")
    }
} else {
    do {
        let fds = try UnixSocketListener.activatedSockets(named: options.socketName)
        guard !fds.isEmpty else { fail("launchd activated no sockets named \(options.socketName)") }
        listenFds = fds
        log("listening (launchd socket activation), \(fds.count) socket(s)")
    } catch {
        fail("launchd activation failed: \(error)")
    }
}

installSignalHandlers()
server.start(listenFds: listenFds)
log("ready, mode=\(options.contractFixtures ? "contract-fixtures" : "real")")
dispatchMain() // services .main (signal sources); never returns

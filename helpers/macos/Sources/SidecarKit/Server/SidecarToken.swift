import Foundation
import Darwin

/// Resolves the in-band auth token the helper validates in the `hello` handshake (peer-auth leg 2,
/// ADR-0009/0024). The token is provisioned **out-of-band** — both peers read the same value:
///
/// 1. `--token <value>` (explicit; used by the contract-parity integration test and dev runs),
/// 2. the `DEFERNO_SIDECAR_TOKEN` environment variable (launchd injects this via the LaunchAgent plist),
/// 3. `--token-file <path>` / `DEFERNO_SIDECAR_TOKEN_FILE` — a `0600` file both peers read.
///
/// The token itself is a secret: it is never logged.
public enum SidecarToken {

    public static let envVar = "DEFERNO_SIDECAR_TOKEN"
    public static let envFileVar = "DEFERNO_SIDECAR_TOKEN_FILE"

    public enum ResolutionError: Error, CustomStringConvertible {
        case missing
        case unreadableFile(String)
        case insecureFile(String)
        case empty

        public var description: String {
            switch self {
            case .missing: return "no sidecar token provided (--token, $\(envVar), or --token-file)"
            case .unreadableFile(let path): return "cannot read token file at \(path)"
            case .insecureFile(let path): return "token file at \(path) must be owner-only (0600) and owned by the current user"
            case .empty: return "sidecar token is empty"
            }
        }
    }

    /// Resolve the token from explicit args first, then env. `environment` is injectable for tests.
    public static func resolve(
        explicit: String? = nil,
        file: String? = nil,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) throws -> String {
        if let explicit, !explicit.isEmpty { return explicit }
        if let value = environment[envVar], !value.isEmpty { return value }
        let filePath = file ?? environment[envFileVar]
        if let filePath {
            // The token file is a secret both peers share — enforce the contract's leg-2 posture: it must
            // be owner-only (0600) and owned by the current user, else another local user could read or
            // plant the token (ADR-0009). Mirrors the client-half PosixPeerTrust on the socket path.
            guard fileIsOwnerOnly(filePath) else { throw ResolutionError.insecureFile(filePath) }
            guard let contents = try? String(contentsOfFile: filePath, encoding: .utf8) else {
                throw ResolutionError.unreadableFile(filePath)
            }
            let token = contents.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty else { throw ResolutionError.empty }
            return token
        }
        throw ResolutionError.missing
    }

    /// True iff `path` is owned by the current user and has no group/other permission bits.
    private static func fileIsOwnerOnly(_ path: String) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: path) else { return false }
        let owner = (attrs[.ownerAccountID] as? NSNumber)?.uint32Value
        let perms = (attrs[.posixPermissions] as? NSNumber)?.uint16Value
        guard let owner, let perms else { return false }
        return owner == getuid() && (perms & 0o077) == 0
    }

    /// Constant-time equality over the UTF-8 bytes of two tokens — the comparison runs over the longer
    /// length and never early-returns, so neither a length difference nor the first differing byte leaks
    /// via timing on the (sole) secret compare.
    public static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8)
        let y = Array(b.utf8)
        var diff: UInt8 = x.count == y.count ? 0 : 1
        let n = max(x.count, y.count)
        var i = 0
        while i < n {
            let xi = i < x.count ? x[i] : 0
            let yi = i < y.count ? y[i] : 0
            diff |= xi ^ yi
            i += 1
        }
        return diff == 0
    }
}

// swift-tools-version:5.9
//
// The macOS Sidecar **helper** (ADR-0024 / ADR-0025, issue #121) — a Developer-ID-signed Swift agent
// that launchd activates on first connect. It serves the language-neutral Sidecar protocol
// (`contracts/sidecar/protocol-v1.md`) over a peer-authenticated AF_UNIX socket: the JVM `core/sidecar`
// client is the *other* implementation of the same wire, so swapping the Linux stub for this helper
// requires **no JVM-side changes**.
//
// Two targets keep the wire/transport/capability logic unit-testable in isolation from the thin entry
// point:
//   • `SidecarKit`        — all logic (protocol codec, AF_UNIX transport, peer-auth, SFSpeech, TCC).
//   • `deferno-sidecar`   — the executable: argument parsing + launchd socket activation + serve loop.
//
// macOS 13 deployment target (Ventura is the OS ceiling of the 2019 Intel dev machine — #115); the
// dictation engine is on-device **SFSpeechRecognizer**, not the macOS-26-only SpeechTranscriber.
import PackageDescription
import Foundation

// Embed the helper's Info.plist (TCC usage strings + stable CFBundleIdentifier) into the bare Mach-O's
// __TEXT,__info_plist section at link time — a launchd agent has no on-disk Info.plist, and TCC reads
// the usage strings from this section through the code-signing subsystem (it becomes visible only once
// the binary is signed). `Context.packageDirectory` makes the path absolute and invariant to the build
// cwd (so `swift build --package-path …` from the repo root embeds it just the same).
let infoPlistPath = Context.packageDirectory + "/Resources/Info.plist"

let package = Package(
    name: "DefernoSidecar",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "deferno-sidecar", targets: ["DefernoSidecarCLI"]),
        // The capability library, reused in-process by the native macOS app (ADR-0029 Phase 2): the same
        // SpeechTranscriber / SidecarPermissions sources the launchd Helper serves over the socket, linked
        // directly into app/macosApp instead. The Linux CLI path is unaffected.
        .library(name: "SidecarKit", targets: ["SidecarKit"]),
    ],
    targets: [
        // SidecarKit is pure Swift + Darwin + Speech/AVFoundation. launchd's launch_activate_socket()
        // is reached via `import launch` (the SDK's own system module) — no C shim / module map needed.
        .target(
            name: "SidecarKit",
            path: "Sources/SidecarKit"
        ),
        .executableTarget(
            name: "DefernoSidecarCLI",
            dependencies: ["SidecarKit"],
            path: "Sources/DefernoSidecarCLI",
            linkerSettings: [
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", infoPlistPath,
                ]),
            ]
        ),
        .testTarget(
            name: "SidecarKitTests",
            dependencies: ["SidecarKit"],
            path: "Tests/SidecarKitTests"
        ),
    ]
)

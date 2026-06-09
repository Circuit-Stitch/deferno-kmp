# Deferno macOS Sidecar helper

The native macOS half of the Sidecar substrate (ADR-0024 / ADR-0025, issue **#121**): a
Developer-ID-signed Swift agent that **launchd activates on first connect** and serves the
language-neutral [Sidecar protocol](../../contracts/sidecar/protocol-v1.md) over a peer-authenticated
AF_UNIX socket. It hosts **on-device dictation** (`SFSpeechRecognizer` + `AVAudioEngine`) and brokers the
macOS **mic + Speech permissions (TCC)** the JVM cannot reach.

The JVM `core/sidecar` client is the *other* implementation of the same wire. Both conform to
`contracts/sidecar/protocol-v1.md` + the golden fixtures, so **swapping the Linux stub for this helper
requires no JVM-side changes** — proven by an integration test that drives the real JVM client against
the real signed binary (see *Testing*).

> **whisper-in-JVM stays the always-available floor** (ADR-0018). This helper is a higher-rank fast path
> the `SpeechToTextSelector` (#119) picks only when it reports genuinely available; absent or denied, the
> app degrades gracefully.

## Layout

```
helpers/macos/
  Package.swift                 SwiftPM manifest (macOS 13 target; embeds Info.plist at link time)
  Sources/
    SidecarKit/                 all logic (unit-testable, no @main)
      Protocol/                 wire: JSONValue, SidecarFrame, codec, protocol constants, payloads
      Transport/                SocketByteStream + UnixSocketListener (launchd activation + self-bind)
      Permissions/              TCC introspect/request (mic + Speech)
      Speech/                   SpeechTranscriber (SFSpeech + AVAudioEngine streaming)
      Server/                   connection handler + capability providers (real + canned) + token
    DefernoSidecarCLI/          the executable: arg parsing, activation, signals, dispatchMain
  Tests/SidecarKitTests/        XCTest: golden-fixture codec + framing + server-over-socketpair
  Resources/
    Info.plist                          embedded TCC usage strings + stable CFBundleIdentifier
    deferno-sidecar.entitlements        hardened-runtime entitlements (audio-input)
    com.circuitstitch.deferno.sidecar.plist.template   LaunchAgent contract (placeholders → #122 install)
  scripts/build.sh              release build (universal) + Developer ID sign
```

## Activation modes

| Mode | How | Used by |
|---|---|---|
| **launchd socket activation** (default) | `launch_activate_socket("Sidecar", …)` inherits launchd's bound, owner-only socket | production |
| **self-bind** | `--listen <path>` binds + `chmod 0600`s the socket itself (models launchd) | dev runs, the JVM contract-parity test |
| **contract-fixtures** | `--contract-fixtures` serves the *canned* stub-parity frames (no TCC/mic) | the contract-parity test |

```
deferno-sidecar --version
deferno-sidecar --listen /tmp/sidecar.sock --token <tok>                 # real engine
deferno-sidecar --listen /tmp/sidecar.sock --token <tok> --contract-fixtures   # canned (stub parity)
```

Token (peer-auth leg 2) comes from `--token`, `$DEFERNO_SIDECAR_TOKEN`, or `--token-file` / a `0600`
file (`$DEFERNO_SIDECAR_TOKEN_FILE`). It is never logged.

## Peer authentication (ADR-0009)

Three complementary legs:

1. **Path trust** — the socket is owner-only `0600` (launchd binds it so, or `--listen` `chmod`s it). The
   client refuses a group/other-accessible path. *(client + this helper's binder)*
2. **In-band token** — the client's first `hello` carries the shared secret; a mismatch is answered with
   a connection-level `failure(unauthenticated)` and the connection closes. *(this helper)*
3. **Kernel peer-credential check** — `getpeereid` must report the connecting peer's uid == the helper's
   uid; a different-user peer is rejected at `accept`, before any frame is read. *(this helper)*

## Build & sign

```sh
scripts/build.sh                          # universal (x86_64+arm64), Developer ID signed → dist/deferno-sidecar
SIDECAR_ARCHS="x86_64" scripts/build.sh   # single-arch (the slice verifiable on an Intel Mac)
SIDECAR_SIGN_IDENTITY="-" scripts/build.sh    # ad-hoc (CI without the cert)
```

- The **Info.plist is embedded** into the Mach-O `__TEXT,__info_plist` section at link time (a launchd
  agent has no on-disk Info.plist). TCC reads the usage strings from there **through the code-signing
  subsystem — they are invisible until the binary is signed**, so signing is functionally required, not
  just for distribution.
- Signed with **hardened runtime** (`--options runtime`) and a stable **Developer ID** signature, so the
  mic/Speech TCC grants **persist across rebuilds**. Notarization + Conveyor packaging is the separate
  **#122**.
- The **entitlements plist must contain no XML comments** — AMFI's strict parser rejects them. The mic
  entitlement `com.apple.security.device.audio-input` is the hardened-runtime audio-input key; the Speech
  capability needs no entitlement (only the `NSSpeechRecognitionUsageDescription` string + the grant).

## launchd install (contract)

`Resources/com.circuitstitch.deferno.sidecar.plist.template` is the on-demand socket-activation
LaunchAgent. Installation — substituting the absolute placeholders, writing to
`~/Library/LaunchAgents/`, and `launchctl bootstrap gui/$(id -u)` — is wired by **Conveyor / first run in
#122**. The socket path **must** equal the client's `SidecarSocketPath.default()` on macOS
(`~/Library/Application Support/Deferno/sidecar.sock`).

## Testing

```sh
swift test                                # SidecarKit: golden-fixture codec, framing, server-over-socketpair
```

The cross-language **contract parity** proof lives on the JVM side and drives the **real signed binary**:

```sh
helpers/macos/scripts/build.sh            # produce dist/deferno-sidecar first
./gradlew :core:sidecar:test --tests '*RealHelperContractParityTest'
```

`RealHelperContractParityTest` spawns the helper in `--contract-fixtures` mode and runs the real
`DefaultSidecarClient` against it, asserting the same shapes as the stub's `SidecarClientE2ETest`
(handshake/capabilities, request/response, push, server stream, cancel, token rejection). It is
**macOS-gated** and self-skips off macOS / when the binary isn't built (like every Mac-only task in this
repo), so Linux/Windows CI is untouched. Point `$DEFERNO_SIDECAR_BINARY` at a binary to override the
default `dist/` location.

## Verified vs. human-gated

What the automated build/tests prove on this machine (Xcode 15.2 / Ventura 13.7.8 / Intel):

- ✅ Builds with Xcode 15.2, macOS 13 deployment target; universal (x86_64 verified, arm64 cross-built).
- ✅ Serves the protocol over the socket and **passes the same client tests the stub did** (the real JVM
  client ↔ real signed binary, contract parity) — no JVM-side changes.
- ✅ Peer authentication: token mismatch → connection-level `unauthenticated`; `getpeereid` uid check
  rejects a non-matching peer at accept.
- ✅ Helper Info.plist usage strings + entitlements present and embedded; Developer-ID hardened-runtime
  signature valid and satisfies its Designated Requirement.
- ✅ Audio never crosses the socket and no payload/Transcript text is logged (the wire layer redacts;
  `NoTranscriptLoggingTest` + the Swift `description` redaction cover this).

What still needs a **human at the GUI** to confirm (these need real consent dialogs / a real voice / a
real launchd session, which can't be automated headlessly):

- ⏳ The real **mic + Speech TCC prompts** firing and being granted (introspection/request code is in
  `SidecarPermissions`; subscribing fires them on first use).
- ⏳ **On-device `SFSpeechRecognizer` producing real Partial/Final transcripts** for a spoken `en-US`
  utterance (requires the on-device asset, a granted Speech+mic grant, and a live voice).
- ⏳ The end-to-end **launchd socket-activation** path under an installed LaunchAgent (the install itself
  is #122; the `launch_activate_socket` code path is built and the self-bind path is fully exercised).

To exercise the real engine manually once granted: `deferno-sidecar --listen /tmp/s.sock --token t`, then
drive it from the desktop app (#119) or a socket client speaking the protocol, and speak.
```

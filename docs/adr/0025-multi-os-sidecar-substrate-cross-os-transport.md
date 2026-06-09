# Multi-OS native-sidecar substrate + cross-OS transport

**Context.** ADR-0024 reaches native **macOS** capabilities through a Developer-ID-signed **Swift
sidecar helper** over a peer-authenticated **Unix-domain socket carrying JSON**, with the JVM client
deliberately **launchd-agnostic** (it connects to a well-known socket *path*; launchd binds the far end
on macOS, a stub binder in Linux tests). We now commit to the same pattern on **Windows and Linux** —
specialized native helpers per OS — and want **named pipes to be a first-class Windows transport**, not
an afterthought. That raises a structural question ADR-0024 left implicit: is "the sidecar" one macOS
thing, or a reusable substrate? The four hard parts of the IPC — **frame codec, correlation,
server→client streams, unsolicited push, the in-band-token handshake, reconnect** — are identical
regardless of what binds the far end; only the **capabilities** a helper offers and the **kernel
peer-credential check** are OS-specific.

**Decision.** Treat the sidecar as a **shared, OS-agnostic substrate**: **one** [[Sidecar protocol]] +
**one** JVM [[Sidecar client]] + **N** per-OS [[Sidecar helper]]s. A new OS is **additive** — a new
helper over the *same* contract — exactly the move ADR-0024 already makes for a new *capability*.

- **Transport is a first-class, pluggable seam.** The codec and client sit entirely above an abstract
  **byte-stream `Connection`** — deliberately **not** a `SocketChannel`. #118 ships `UnixSocketTransport`
  only (JDK 17 AF_UNIX, JEP 380 — which already serves macOS, Linux, **and** Windows 10 1803+); a
  Windows-native **`NamedPipeTransport` is a committed drop-in** that lands with the Windows helper. A
  named pipe can't yield a `SocketChannel` but can satisfy a byte-stream `Connection`, so keeping the
  boundary at raw bytes is what makes a new transport touch **zero** existing code. Floor: **Windows 10
  1803+**, so AF_UNIX is *possible* everywhere and named pipes are an *option*, chosen on Windows for the
  idiomatic ACL-based security.
- **Peer-auth is a separate, per-transport seam (`PeerTrust`), never a portable lowest-common-
  denominator.** Folding it into the transport would force the Unix check down to whatever Windows can
  also do. Instead each transport keeps its **full-strength, OS-appropriate** check: the Unix transport
  verifies socket-path **ownership + `0600`**; a pipe transport will check the pipe **ACL/SID**. The
  **in-band token** ([SidecarFrame.Hello]/`Welcome`) is the transport-agnostic third leg. This is the
  **client half**; the **server-half kernel check** (`getpeereid`/`SO_PEERCRED`) is each helper's job —
  the JVM can't do it portably and shouldn't.
- **Capabilities are negotiated, not assumed.** A helper advertises its `protocolVersion` + a
  **capability set** in `Welcome`; the client surfaces them so a consumer **degrades gracefully** against
  a helper that lacks a capability (e.g. the `SpeechToTextSelector` keeps whisper-in-JVM, ADR-0018).
- **The client + contract are a JVM-only, isolated leaf.** They live in `core/sidecar`, applying a new
  **`deferno.jvm.library`** convention (the repo's first non-KMP core module — anticipated by the note in
  `deferno.kmp`). It is JVM-only **by design**: the helpers are native processes (Swift/…), not Kotlin,
  so KMP targets would be permanent dead weight. It depends on **nothing** in `core/*` — it owns its
  *wire* DTOs and the domain mapping happens **at the edge** in the consumers (`core/speech` #119,
  permission ports #120), per ADR-0011. The language-neutral **spec + golden fixtures** live in
  `contracts/sidecar/` — the actual cross-codebase/cross-language contract every helper (any language)
  implements against. Apache-2 (ADR-0020) + zero `core/*` coupling means **extracting it to its own repo
  later is a lift, not a detangle** — but it stays in-repo now because the contract co-evolves in lockstep
  with its in-repo consumers and helpers (a separate repo would turn every additive capability into a
  cross-repo publish/bump cycle).
- **Framing: 4-byte big-endian length prefix + UTF-8 JSON**, 1 MiB cap. Robust against partial reads and
  binary-clean — trivially implementable by a Swift/C#/Rust helper.

**Considered & rejected.**
- **Per-OS protocols/clients** — the substrate is genuinely shared; this would triplicate the hard parts
  and let three contracts drift.
- **Folding peer-auth into the transport as one portable check** — would weaken the Unix `0600` check to
  a lowest-common-denominator; auth is inherently a per-OS kernel mechanism.
- **Exposing `SocketChannel` from the transport seam** — a named pipe can't produce one, so this would
  force a codec rewrite the day Windows lands; the byte-stream boundary is the whole point.
- **NDJSON framing** — length-prefix is more robust and binary-clean for a cross-language contract.
- **`core/sidecar` as a KMP library** — it is JVM-only by design; KMP targets would be permanent dead
  weight on a module no Apple/Android target will ever use.
- **A separate repo now** — premature: the contract co-evolves with in-repo consumers + helpers, and the
  ceremony fights ADR-0024's "Linux-authorable tracer bullet." Kept an extractable leaf instead
  ("earn the split", ADR-0004).

**Consequences.** The repo gains its **first JVM-only core module** and the **`deferno.jvm.library`**
convention (a reusable primitive). #118 ships the contract + client + a **Linux stub helper**, **fully
Linux-verifiable with no Mac** (the end-to-end test drives the real client over a real Unix socket).
`SidecarSpeechToText` (#119), the permission ports (#120), the macOS Swift helper (#121), and future
Windows/Linux helpers + deferred capabilities (#123–126) all ride on this seam. **Adding a new OS** means
adding a `Transport` (only if not AF_UNIX), a `PeerTrust`, and capability methods — **without reshaping
the protocol**. Every native helper, in any language, conforms to `contracts/sidecar/protocol-v1.md` and
the golden fixtures.

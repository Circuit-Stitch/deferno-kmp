# Sidecar protocol — v1

The language-neutral contract between the JVM **Sidecar client** (`core/sidecar`) and a per-OS native
**Sidecar helper** (Swift on macOS; Windows/Linux later). See ADR-0024 (why a sidecar) and ADR-0025 (the
multi-OS substrate + transport strategy). This document + the golden frames in `./fixtures/` **are** the
contract: any helper, in any language, implements against them. The Kotlin types in `core/sidecar` and a
helper's native types are two independent implementations of this one spec.

`protocolVersion` for this document is **1**.

## Transport

A local, bidirectional, reliable **byte stream** between exactly two peers on one machine:

- **v1:** a **Unix-domain socket** (`AF_UNIX`, `SOCK_STREAM`) at a well-known, configurable filesystem
  path. Serves macOS, Linux, and Windows 10 1803+.
- **planned:** a **Windows named pipe** (first-class; ADR-0025). The framing and messages below are
  identical over either — the protocol is transport-agnostic.

The client dials a configurable path (default per-OS, e.g. `$XDG_RUNTIME_DIR/deferno/sidecar.sock` on
Linux, `~/Library/Application Support/Deferno/sidecar.sock` on macOS). **Connection-absent** (path
missing, nothing listening, connection refused) is a normal state: the client degrades gracefully.

## Peer authentication

Three complementary legs (ADR-0009/0024). v1 specifies the first two on the client; the third is the
helper's:

1. **Path trust (client half).** Before connecting, the client verifies the socket path is **owned by
   the current user** and **not group/other-accessible** (mode `0600`/`0700` on POSIX; the equivalent ACL
   check on a Windows pipe). The helper (or its launcher) **must** create the socket owner-only.
2. **In-band token.** The client's first frame is `hello` carrying a shared secret `token`, provisioned
   out-of-band (launchd env / a `0600` file both peers read — helper's concern). The helper validates it
   and replies `welcome`; on mismatch it replies a connection-level `failure` (`unauthenticated`) and
   closes.
3. **Kernel peer-credential check (server half, helper's responsibility).** The helper verifies the
   connecting client's uid with `getpeereid`/`SO_PEERCRED` (POSIX) or the pipe's client token (Windows).

## Framing

Each frame is a **4-byte big-endian unsigned length prefix** followed by that many bytes of **UTF-8
JSON**. Implementations **must** reject a length over **1 MiB** (`1048576`) without allocating. The JSON
is a single object tagged with a `"type"` discriminator. No newline delimiting; the length prefix is
authoritative.

## Frames

Every frame object has a `"type"` field. Unknown fields **must** be ignored (forward-compatible). Fields
with a default/absent value (a null `params`, an empty `capabilities`) are omitted on the wire.

| `type` | Direction | Fields | Meaning |
|---|---|---|---|
| `hello` | client → helper | `token: string`, `protocolVersion: int` | Open the handshake. |
| `welcome` | helper → client | `protocolVersion: int`, `capabilities: string[]` | Handshake accepted; advertise capabilities. |
| `request` | client → helper | `id: int`, `method: string`, `params?: object` | Invoke a method (unary or stream-opening). |
| `response` | helper → client | `id: int`, `result?: any` | Unary success for `request id`. |
| `stream_data` | helper → client | `id: int`, `event: any` | One item of the server stream for `request id`. |
| `stream_end` | helper → client | `id: int` | The server stream for `request id` completed. |
| `cancel` | client → helper | `id: int` | Stop the server stream for `request id`. |
| `push` | helper → client | `topic: string`, `payload: any` | Unsolicited event (no correlation id). |
| `failure` | helper → client | `id?: int`, `error: {code, message, details?}` | Error for `request id`, or **connection-level** if `id` is absent. |

Correlation `id`s are positive integers chosen by the client, unique among in-flight requests.

## Traffic shapes

- **Request/response.** `request{id,method,params}` → `response{id,result}` **or** `failure{id,error}`.
- **Server stream.** `request{id,method,params}` → zero or more `stream_data{id,event}` → `stream_end{id}`
  (**or** `failure{id,error}`). The client may send `cancel{id}` to stop early; the helper then ceases
  work (e.g. releases the mic) and need not send `stream_end`.
- **Unsolicited push.** `push{topic,payload}` at any time after `welcome`, uncorrelated.

## Error model

`error` is `{ "code": string, "message": string, "details"?: any }`. `message` is a **non-PII**
human-readable summary; `details` is opaque. v1 codes (a helper may send others; the client coerces an
unknown code to `unknown`):

| `code` | Meaning |
|---|---|
| `unknown_method` | The helper does not implement `method`. |
| `invalid_params` | `params` were missing or malformed. |
| `unauthenticated` | Handshake rejected (bad/absent token). Connection-level (`failure` with no `id`). |
| `unavailable` | The capability exists but isn't available now (permission denied, engine busy). |
| `internal` | The helper failed internally. |
| `protocol` | A protocol violation (unframeable/oversize/unexpected frame). |
| `unknown` | Client-side fallback for an unrecognized code. |

## Versioning

`hello`/`welcome` exchange `protocolVersion`. Readers are tolerant (ignore unknown fields, coerce unknown
enum values), so additive changes (new methods, topics, capabilities, fields) are **non-breaking** and
do **not** bump the version. Bump only on a breaking wire change.

## v1 capabilities, methods & topics

The seed surface that proves the three traffic shapes (#118); it grows additively (#119/#120/#123+).

- **Capability `permissions`** — methods `queryPermission` / `requestPermission` (request/response →
  `PermissionStatus`) and topic `permissionChanged` (push → `PermissionStatus`). Both take optional
  params `{ "capability": string }` naming which permission (default `"speech"`); the response echoes
  that capability. `queryPermission` introspects and **never prompts** — a capability's first real use
  fires the OS prompt on its own (`subscribeTranscript`, `postNotification`), pushing
  `permissionChanged` as the state settles. `requestPermission` (#120) resolves the permission
  *without* engaging the capability (no capture starts): on `not_determined` it fires the OS
  authorization prompt, answers with the **settled** state, and pushes `permissionChanged`; in any
  other state it just reports the current state without prompting (an OS denial is terminal —
  re-requesting never re-prompts; only the OS settings surface flips it).
  `PermissionStatus = { "capability": string, "status": "granted"|"denied"|"not_determined"|"restricted"|"unknown" }`
  v1 permission capability ids: `"mic"`, `"speech"`, `"notifications"` (#123).
- **Capability `speech.transcribe`** — method `subscribeTranscript` (server stream of `TranscriptEvent`).
  `TranscriptEvent` is `{ "type": "partial", "text": string }` | `{ "type": "final", "text": string }` |
  `{ "type": "failure", "reason": string }` (a non-PII reason).
  **Dictation prompt order (#172):** dictation needs two permission gates — `"speech"` and `"mic"` —
  and they resolve **in that order**: a Helper's own first-use authorization (`subscribeTranscript`
  against undetermined gates) prompts speech recognition first, then the microphone, and a client
  running a `requestPermission` preflight MUST issue the same sequence, so the person sees the same
  two prompts in the same order regardless of which side initiates.
- **Capability `notifications`** (#123) — method `postNotification` (request/response → empty ack).
  `params = { "title": string, "body"?: string }`; `title` must be non-empty (`invalid_params`
  otherwise). The first post against a `not_determined` permission fires the OS authorization prompt
  (pushing `permissionChanged` with capability `"notifications"` as it settles); posting without a
  grant fails `unavailable` (`notification-permission-denied`), and an OS-level delivery error fails
  `internal` (`notification-post-failed`). The title/body are user content — the payload privacy rules
  below apply to them in full.
- **Capability `statusItem`** (#125) — method `setStatusItem` (request/response → empty ack) and topic
  `statusItemClicked` (push → `{}`). `params = { "visible": boolean }` (`invalid_params` when absent).
  While visible, each click on the helper's menu-bar status item pushes `statusItemClicked`; the
  helper removes the item when the requesting connection closes, so it appears only while the app runs.
- **Capability `hotkeys`** (#125) — methods `registerHotkey` / `unregisterHotkey` (request/response →
  empty ack) and topic `hotkeyFired` (push → `{ "id": int }`).
  `RegisterHotkeyRequest = { "id": int, "key": string, "modifiers": string[] }` — `id` is the
  client-chosen handle echoed back in every `hotkeyFired` push; registering an already-registered `id`
  **replaces** it. `key` is a single character `a`–`z` / `0`–`9` or a named key (`space`, `return`,
  `escape`, `tab`, `f1`–`f12`), interpreted at the **ANSI key position** (layout-independent);
  `modifiers` is a non-empty subset of `command` | `option` | `control` | `shift`. An unknown
  key/modifier or empty modifier list is `invalid_params`; an OS-level registration rejection is
  `unavailable` (`hotkey-unavailable`). `unregisterHotkey` params are `{ "id": int }` (idempotent —
  unknown ids ack). The helper unregisters all of a connection's hotkeys when it closes.

## Privacy

The socket carries privacy-critical **Transcript** text (ADR-0009/0018). The recognized audio never
crosses this seam — the helper emits **text**, not PCM. Neither peer may **log** payloads
(`stream_data.event`, `push.payload`, `request.params`, `response.result`, `error.details`) or Transcript
text; diagnostics carry **metadata only** (type, id, method, topic, error code).

## Golden fixtures

`./fixtures/*.json` are canonical example frames. A helper's encoder/decoder should round-trip them; the
client's `SidecarFrameSerializationTest` decodes them to prove the shipping code matches this spec.

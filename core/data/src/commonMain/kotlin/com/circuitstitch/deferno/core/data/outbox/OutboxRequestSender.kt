package com.circuitstitch.deferno.core.data.outbox

/**
 * How a single replay attempt resolved (ADR-0001, #23) — the three outcomes the [OutboxProcessor]
 * dispatches on. Deliberately coarse: the outbox doesn't care *what* came back, only whether to drop
 * the entry, back off and retry it, or give up on it.
 *
 * - [Success] — the server accepted the write (a `2xx`), or it is already in the intended end state
 *   (a `404` on a DELETE/PATCH whose target is gone — idempotent under LWW). Drop the entry.
 * - [Retryable] — a *transient* failure that a later replay may clear: no response at all (transport /
 *   timeout / TLS), a `5xx`, a `429` rate-limit, a `408`, or a `401` (auth expired — the write is kept
 *   queued across a re-auth rather than lost). Back off and retry.
 * - [Terminal] — the server rejected the intent **on its merits** (a non-auth `4xx`: `400`/`403`/
 *   `409`/`422`, …): retrying the same bytes can't help, so drop it (LWW — the local optimistic value
 *   is reconciled back to server truth on the next refresh).
 */
enum class SendOutcome { Success, Retryable, Terminal }

/**
 * The network port the [OutboxProcessor] replays an [OutboxRequest] through (ADR-0001, #23). Speaks
 * only [SendOutcome], so the replay policy never touches HTTP. Extracting it behind a port keeps the
 * FIFO/backoff/head-of-line engine unit-testable against a scriptable fake (ADR-0006 JVM-fast path),
 * while [KtorOutboxRequestSender] proves the real HTTP mapping.
 */
interface OutboxRequestSender {

    /** Replays [request] and classifies the result into a [SendOutcome]. Never throws on HTTP status. */
    suspend fun send(request: OutboxRequest): SendOutcome
}

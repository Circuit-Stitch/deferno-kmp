package com.circuitstitch.deferno.core.data.outbox

/**
 * Scriptable [OutboxRequestSender] for the replay-engine tests (#23). Records every request it is
 * asked to [send] (in order — so a test can assert FIFO replay and that the bytes are identical across
 * retries), and decides each outcome by, in priority: a per-request [decide] predicate, then a
 * one-shot [script] queue, then the steady [outcome]. The default is [SendOutcome.Success].
 */
class FakeOutboxRequestSender(
    var outcome: SendOutcome = SendOutcome.Success,
) : OutboxRequestSender {

    /** Every request sent, in dispatch order (includes re-sends of a retried entry). */
    val sent = mutableListOf<OutboxRequest>()

    /** Consumed in order when set — each entry overrides [outcome] for one [send]. */
    var script: ArrayDeque<SendOutcome>? = null

    /** Highest-priority override: decides the outcome from the request itself (e.g. fail one path). */
    var decide: ((OutboxRequest) -> SendOutcome)? = null

    override suspend fun send(request: OutboxRequest): SendOutcome {
        sent += request
        decide?.let { return it(request) }
        script?.let { if (it.isNotEmpty()) return it.removeFirst() }
        return outcome
    }
}

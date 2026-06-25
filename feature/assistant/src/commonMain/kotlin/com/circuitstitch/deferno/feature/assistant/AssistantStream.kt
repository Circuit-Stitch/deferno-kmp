package com.circuitstitch.deferno.feature.assistant

import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The fake-able streaming seam for an [[Assistant]] turn (issue #282, ADR-0040). [streamTurn] opens one
 * turn and emits [AssistantEvent]s as the server-mediated reply streams. The iOS `actual` is a Swift
 * URLSession SSE transport bridged in at `DefernoRoot` (the native-engine pattern of ADR-0029/0037);
 * tests drive the component with a scripted fake, so the chat state machine is exercised with no real
 * network or SSE. Android/desktop transports follow later (the seam keeps `ktor-client-sse` an option).
 */
interface AssistantStream {

    fun streamTurn(request: AssistantTurnRequest): Flow<AssistantEvent>

    companion object {
        /**
         * The inert default (the optional-seam convention, e.g. core/agent's no-op engine): when no
         * transport is wired (a non-iOS host, or before Deferno#485 lands) a turn surfaces a graceful
         * "not available here" [AssistantEvent.Error] rather than hanging or silently producing nothing.
         */
        val NONE: AssistantStream = object : AssistantStream {
            override fun streamTurn(request: AssistantTurnRequest): Flow<AssistantEvent> =
                flowOf(AssistantEvent.Error("The Assistant isn't available on this device yet."))
        }
    }
}

/**
 * One turn request: which [[Conversation]] it extends and the person's message. The client mints the
 * [conversationId] for a brand-new chat (client-supplied ids, like offline creates — #185), so the id is
 * always known before the turn streams; the iOS transport pairs it with auth + base URL.
 */
data class AssistantTurnRequest(
    val conversationId: ConversationId,
    val message: String,
)

/**
 * The **provisional** SSE event taxonomy (ADR-0040) — reconciled with the real backend wire format
 * (Deferno#485) before the live transport is wired. Kept isolated here + in the iOS parser, so a wire
 * change is contained: the component is tested against these typed events.
 */
sealed interface AssistantEvent {

    /** A chunk of the assistant's reply text — accrued onto the streaming message. */
    data class TextDelta(val text: String) : AssistantEvent

    /** The assistant invoked a read/ordinary-write tool (surfaced as activity, not a confirmation). */
    data class ToolCall(val tool: String, val input: String) : AssistantEvent

    /** A tool returned — informational. */
    data class ToolResult(val tool: String, val output: String) : AssistantEvent

    /** A gated change awaiting the person's yes/no — surfaced as an inline confirm card. */
    data class Proposal(val proposal: AssistantProposal) : AssistantEvent

    /** Token-pool usage. [exhausted] hard-stops the surface with a clear message (the only field v1 uses). */
    data class Usage(val remaining: Int? = null, val exhausted: Boolean = false) : AssistantEvent

    /** The turn finished cleanly. */
    data object Done : AssistantEvent

    /** The turn failed (mid-stream drop, server error) — surfaced gracefully without breaking the chat. */
    data class Error(val message: String) : AssistantEvent
}

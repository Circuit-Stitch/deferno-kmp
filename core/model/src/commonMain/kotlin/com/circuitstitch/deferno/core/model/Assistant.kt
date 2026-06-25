package com.circuitstitch.deferno.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * The server-mediated conversational [[Assistant]] domain types (issue #282, ADR-0040). Plain Kotlin
 * value/data types — no serialization, no platform deps — so they cross the iOS/SKIE bridge and back
 * the shared `feature/assistant` state. The wire DTOs (`core/network`) map onto these at the boundary;
 * the SQLDelight conversation cache (`core/database`/`core/data`) persists [ChatMessage]s as they stream.
 *
 * Distinct from the on-device propose-only [[Agent]] (ADR-0027): the Assistant is backend-run, writes
 * server-side, and shares only the propose-then-accept pattern (here, [AssistantProposal]).
 */

/**
 * Stable identifier of a [Conversation] — the **server-assigned** id of one Assistant chat thread
 * (`GET …/assistant/conversations` lists these). A faithful, non-blank copy of the wire id, exactly as
 * [TaskId] is for a Task.
 */
@JvmInline
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ConversationId must not be blank" }
    }
}

/**
 * One [[Conversation]]: a server-assigned id, an optional human title, and when it was last touched.
 * The lightweight row the multi-conversation switcher lists (the heavy [ChatMessage] log is loaded on
 * open). The client caches this locally so the switcher reads offline (ADR-0040).
 */
data class Conversation(
    val id: ConversationId,
    val title: String? = null,
    val updatedAt: Instant,
)

/** Who authored one [ChatMessage] in a [Conversation]. */
enum class ChatRole {
    /** The person's prompt. */
    User,

    /** The Assistant's (streamed) reply. */
    Assistant,
}

/**
 * One message in a [[Conversation]] — the person's prompt or the Assistant's reply. [text] accrues
 * token-by-token while a reply streams. Flat + primitive so the SQLDelight cache needs no column
 * adapters and the type bridges to Swift cleanly. An [[Assistant proposal]] is **live state** on the
 * component (not a message); its *outcome* is appended as an ordinary [ChatRole.Assistant] message.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Instant,
)

/**
 * An [[Assistant proposal]] (ADR-0040): a **gated change** the Assistant surfaces mid-turn for the
 * person's explicit yes/no. Only destructive / bulk / cross-[[Org]] changes become proposals; ordinary
 * writes the Assistant performs itself. Confirming applies it **server-side** (`POST …/assistant/apply`,
 * re-checked against the same gate — an out-of-surface proposal can never be applied); rejecting is a
 * client-side discard.
 *
 * [input] is the tool input as **opaque raw JSON text** — the client never interprets it, it sends it
 * back verbatim on apply (the network DTO re-parses it to a JSON value). [summary] is the human-legible
 * "exactly what this will do."
 */
data class AssistantProposal(
    val tool: String,
    val input: String,
    val summary: String,
)

/**
 * The per-[[Org]] [[Availability]] gate every Assistant surface checks (ADR-0040): [entitled] is whether
 * the Org *may* have the Assistant (billing/staff-set); [enabled] is whether the Owner turned it on
 * (which carries the egress consent). [available] is the single gate `entitled && enabled` — when false
 * the chat surface is hidden, not merely disabled.
 *
 * [disclosure] is the egress-consent text the enable surface shows before enabling (the one path where
 * decrypted item content leaves the device to a third-party AI sub-processor). It is carried here so the
 * Destination empty state *and* the Settings row can both render it (issue #282). Usage-exhaustion is a
 * *runtime* signal from the turn stream, not part of this gate.
 */
data class AssistantAvailability(
    val entitled: Boolean,
    val enabled: Boolean,
    val disclosure: String? = null,
) {
    val available: Boolean get() = entitled && enabled
}

/**
 * One [[Conversation]] fetched whole from the server (`GET …/assistant/conversations/{id}`, Deferno#485):
 * the lightweight [conversation] row plus its ordered [messages] log. Drives cross-device hydration — the
 * client merges any [messages] the local cache is missing (ADR-0040).
 */
data class ConversationDetail(
    val conversation: Conversation,
    val messages: List<ChatMessage>,
)

package com.circuitstitch.deferno.feature.assistant

import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId

/**
 * Observable state for the [[Assistant]] chat (#282, ADR-0040). One immutable, all-defaulted, plain-types
 * data class (no Compose/platform types) so it backs the SwiftUI View across the SKIE bridge. Computed
 * guards are `get()` properties, never stored fields.
 *
 * [availability] is `null` while the gate is still loading. The surface has three resting shapes the View
 * picks via the helpers below: hidden (the shell omits the Destination when `!entitled`), the enable +
 * consent CTA ([needsEnable]), and the chat ([available]). [usageExhausted] is a runtime hard-stop from
 * the turn stream, separate from the gate.
 */
data class AssistantState(
    val availability: AssistantAvailability? = null,
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: ConversationId? = null,
    val messages: List<ChatMessage> = emptyList(),
    val composer: String = "",
    val streaming: Boolean = false,
    val online: Boolean = true,
    val pendingProposal: AssistantProposal? = null,
    val applyingProposal: Boolean = false,
    val showingDisclosure: Boolean = false,
    val enabling: Boolean = false,
    val usageExhausted: Boolean = false,
    val error: String? = null,
) {
    /** The gate is on — render the chat. */
    val available: Boolean get() = availability?.available == true

    /** Entitled but the Owner hasn't enabled it — render the enable + egress-consent CTA. */
    val needsEnable: Boolean get() = availability?.let { it.entitled && !it.enabled } == true

    /** The consent text to show on the enable CTA (the API's string, or [AssistantAvailability]'s fallback). */
    val disclosure: String get() = availability?.disclosureOrDefault ?: AssistantAvailability.DEFAULT_DISCLOSURE

    /** Whether a typed message can be sent right now (the composer's enabled guard). */
    val canSend: Boolean
        get() = available && online && !streaming && !usageExhausted &&
            pendingProposal == null && composer.isNotBlank()
}

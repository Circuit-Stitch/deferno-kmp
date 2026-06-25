package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.ChatRole
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationDetail
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.network.dto.AssistantAvailabilityDto
import com.circuitstitch.deferno.core.network.dto.AssistantMessageDto
import com.circuitstitch.deferno.core.network.dto.ConversationDetailDto
import com.circuitstitch.deferno.core.network.dto.EnablementResponseDto
import kotlin.time.Instant

/**
 * Condenses the wire [[Assistant]] DTOs (#282, ADR-0040) to the clean domain types at the network
 * boundary (ADR-0011). Defensive throughout: an unknown role degrades to [ChatRole.Assistant] and an
 * unparseable timestamp to [Instant.DISTANT_PAST] rather than throwing (cf. the settings mapper).
 */

fun AssistantAvailabilityDto.toDomain(): AssistantAvailability =
    AssistantAvailability(entitled = entitled, enabled = enabled, disclosure = disclosure)

/**
 * The enablement PUT returns only `{enabled, disclosure}`, so reconstruct the full gate: a successful
 * enablement implies the Org is **entitled** (the server 403s an un-entitled Org), so `entitled = true`.
 */
fun EnablementResponseDto.toAvailability(): AssistantAvailability =
    AssistantAvailability(entitled = true, enabled = enabled, disclosure = disclosure)

fun ConversationDetailDto.toDomain(): ConversationDetail {
    val log = messages.map { it.toChatMessage() }
    val updated = updatedAt?.toInstantOrNull() ?: log.maxOfOrNull { it.createdAt } ?: Instant.DISTANT_PAST
    return ConversationDetail(
        conversation = Conversation(id = ConversationId(id), title = title, updatedAt = updated),
        messages = log,
    )
}

fun AssistantMessageDto.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    role = if (role.equals("user", ignoreCase = true)) ChatRole.User else ChatRole.Assistant,
    text = text,
    createdAt = createdAt?.toInstantOrNull() ?: Instant.DISTANT_PAST,
)

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

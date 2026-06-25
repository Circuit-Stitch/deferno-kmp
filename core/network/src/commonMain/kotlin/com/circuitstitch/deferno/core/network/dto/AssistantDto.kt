package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the server-mediated [[Assistant]] endpoints (issue #282, ADR-0040), all under
 * `/orgs/{org_id}/assistant…` (`org_id = personal_org_id` in v1). Faithful flat shapes decoded by the
 * tolerant reader ([com.circuitstitch.deferno.core.network.DefernoJson]); every optional field is
 * nullable/defaulted so additive backend changes never break parsing.
 *
 * The request/response endpoints below are stable; the SSE **turn-stream** event taxonomy is provisional
 * and lives in `feature/assistant` (`AssistantEvent`), reconciled with the backend (Deferno#485) before
 * the live transport is wired.
 */

/** `GET …/assistant` → the per-Org [[Availability]] gate (`available = entitled && enabled`). */
@Serializable
data class AssistantAvailabilityDto(
    @SerialName("available") val available: Boolean = false,
    @SerialName("entitled") val entitled: Boolean = false,
    @SerialName("enabled") val enabled: Boolean = false,
    // The egress-consent text the enable surface shows; may also arrive on the enablement response.
    @SerialName("disclosure") val disclosure: String? = null,
)

/** `PUT …/assistant/enablement` body — the Owner turning the Assistant on/off (the egress consent). */
@Serializable
data class EnablementRequestDto(
    @SerialName("enabled") val enabled: Boolean,
)

/** `PUT …/assistant/enablement` → the new enabled state, with the one-time consent [disclosure]. */
@Serializable
data class EnablementResponseDto(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("disclosure") val disclosure: String? = null,
)

/**
 * `POST …/assistant/apply` body — a gated [[Assistant proposal]] the person confirmed. [input] is the
 * tool input as an opaque JSON value, sent back verbatim (the server re-checks it against the same gate,
 * so an out-of-surface proposal can never be applied).
 */
@Serializable
data class AssistantProposalDto(
    @SerialName("tool") val tool: String,
    @SerialName("input") val input: JsonElement,
    @SerialName("summary") val summary: String,
)

/** `POST …/assistant/apply` → whether the change was applied, plus an opaque server [result]. */
@Serializable
data class ApplyResultDto(
    @SerialName("applied") val applied: Boolean = false,
    @SerialName("result") val result: JsonElement? = null,
)

/** `GET …/assistant/conversations` → recent [[Conversation]] ids, most-recent first (ids only). */
@Serializable
data class ConversationsResponseDto(
    @SerialName("conversations") val conversations: List<String> = emptyList(),
)

/**
 * `GET …/assistant/conversations/{conversation_id}` → one Conversation's full message log (the
 * get-messages endpoint, Deferno#485). Drives cross-device hydration: the client renders its local
 * cache immediately and backfills any messages this returns that the cache is missing (ADR-0040).
 */
@Serializable
data class ConversationDetailDto(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("messages") val messages: List<AssistantMessageDto> = emptyList(),
)

/** One message in a [ConversationDetailDto]. [role] is `user` / `assistant` on the wire. */
@Serializable
data class AssistantMessageDto(
    @SerialName("id") val id: String,
    @SerialName("role") val role: String,
    @SerialName("text") val text: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

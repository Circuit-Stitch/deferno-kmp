package com.circuitstitch.deferno.core.data.assistant

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ConversationDetail
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ApplyResultDto
import com.circuitstitch.deferno.core.network.dto.AssistantAvailabilityDto
import com.circuitstitch.deferno.core.network.dto.AssistantProposalDto
import com.circuitstitch.deferno.core.network.dto.ConversationDetailDto
import com.circuitstitch.deferno.core.network.dto.ConversationsResponseDto
import com.circuitstitch.deferno.core.network.dto.EnablementRequestDto
import com.circuitstitch.deferno.core.network.dto.EnablementResponseDto
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.toAvailability
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonPrimitive

/**
 * The production [AssistantClient] over the shared Deferno [HttpClient] (#282, ADR-0040). Each call builds
 * its `/orgs/{org_id}/assistant…` path and goes through the same `requestApi` pipeline every endpoint
 * uses — the Bearer plugin (Active Account PAT), the tolerant reader, the envelope unwrap, the version
 * gate — condensing the wire DTO to the domain type at the boundary (ADR-0011).
 */
class KtorAssistantClient(
    private val client: HttpClient,
) : AssistantClient {

    override suspend fun availability(orgId: OrgId): RemoteSnapshot<AssistantAvailability> =
        client.requestApi<AssistantAvailabilityDto> {
            url { appendPathSegments("orgs", orgId.value, "assistant") }
        }.map { it.toDomain() }.asSnapshot()

    override suspend fun setEnablement(orgId: OrgId, enabled: Boolean): RemoteSnapshot<AssistantAvailability> =
        client.requestApi<EnablementResponseDto> {
            method = HttpMethod.Put
            url { appendPathSegments("orgs", orgId.value, "assistant", "enablement") }
            contentType(ContentType.Application.Json)
            setBody(EnablementRequestDto(enabled = enabled))
        }.map { it.toAvailability() }.asSnapshot()

    override suspend fun apply(orgId: OrgId, proposal: AssistantProposal): RemoteSnapshot<Boolean> =
        client.requestApi<ApplyResultDto> {
            method = HttpMethod.Post
            url { appendPathSegments("orgs", orgId.value, "assistant", "apply") }
            contentType(ContentType.Application.Json)
            setBody(proposal.toDto())
        }.map { it.applied }.asSnapshot()

    override suspend fun conversations(orgId: OrgId): RemoteSnapshot<List<ConversationId>> =
        client.requestApi<ConversationsResponseDto> {
            url { appendPathSegments("orgs", orgId.value, "assistant", "conversations") }
        }.map { dto -> dto.conversations.map { ConversationId(it) } }.asSnapshot()

    override suspend fun conversation(orgId: OrgId, id: ConversationId): RemoteSnapshot<ConversationDetail> =
        client.requestApi<ConversationDetailDto> {
            url { appendPathSegments("orgs", orgId.value, "assistant", "conversations", id.value) }
        }.map { it.toDomain() }.asSnapshot()
}

/**
 * The apply body. The proposal's opaque [AssistantProposal.input] is raw JSON text the client never
 * interprets — re-parse it to a JSON value so it rides the wire as the same structure the server emitted;
 * if it somehow isn't valid JSON, fall back to a JSON string so the call still goes out (the server's
 * own gate is the real check).
 */
private fun AssistantProposal.toDto(): AssistantProposalDto = AssistantProposalDto(
    tool = tool,
    input = runCatching { DefernoJson.parseToJsonElement(input) }.getOrElse { JsonPrimitive(input) },
    summary = summary,
)

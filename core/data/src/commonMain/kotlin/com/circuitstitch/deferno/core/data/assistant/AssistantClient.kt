package com.circuitstitch.deferno.core.data.assistant

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ConversationDetail
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId

/**
 * The request/response half of the [[Assistant]] backend (issue #282, ADR-0040) — everything except the
 * SSE turn stream (that is the fake-able `AssistantStream` seam in `feature/assistant`). All endpoints
 * are `/orgs/{org_id}/assistant…` with `org_id = personal_org_id` in v1; the shared client attaches the
 * Bearer PAT and unwraps the standard `Envelope<T>` + ADR-0005 version gate.
 *
 * Each call returns a [RemoteSnapshot] — the core/data boundary type that collapses every transport/4xx
 * failure to [RemoteSnapshot.Unavailable] — so consumers (the `feature/assistant` component) never see
 * the network's `ApiResult`/`ApiError` (the layering the repositories keep).
 */
interface AssistantClient {

    /** `GET …/assistant` — the per-Org [[Availability]] gate (`available = entitled && enabled`). */
    suspend fun availability(orgId: OrgId): RemoteSnapshot<AssistantAvailability>

    /**
     * `PUT …/assistant/enablement` — the Owner turning the Assistant on/off (the egress consent).
     * Returns the resulting [[Availability]] (entitled implied; carries the one-time disclosure).
     */
    suspend fun setEnablement(orgId: OrgId, enabled: Boolean): RemoteSnapshot<AssistantAvailability>

    /**
     * `POST …/assistant/apply` — apply a confirmed [[Assistant proposal]] server-side (re-checked against
     * the same gate). Returns whether it was applied, so the client can trigger a re-sync of the change.
     */
    suspend fun apply(orgId: OrgId, proposal: AssistantProposal): RemoteSnapshot<Boolean>

    /** `GET …/assistant/conversations` — recent [[Conversation]] ids, most-recent first. */
    suspend fun conversations(orgId: OrgId): RemoteSnapshot<List<ConversationId>>

    /** `GET …/assistant/conversations/{id}` — one Conversation's full message log (Deferno#485). */
    suspend fun conversation(orgId: OrgId, id: ConversationId): RemoteSnapshot<ConversationDetail>
}

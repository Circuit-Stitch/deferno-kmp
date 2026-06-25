package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.assistant.AssistantClient
import com.circuitstitch.deferno.core.data.assistant.ConversationStore
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * The shell's inert/default seams (ADR-0040, #74, #375) and small mappers, pulled out of
 * [DefaultMainShellComponent] so the per-Destination defaults live next to each other, not interleaved with
 * the nav state machine (#284). All `internal` so the shell impl and its tests share one definition each.
 */

/**
 * Map a persisted [BrainDumpDraft] to the online-only create payload (ADR-0015 Inbox amendment) — the
 * Inbox accept path. The persisted draft is **flat** (no inter-draft parent/child/sequence relations —
 * dropped at extraction, ADR-0027 flat-create), so this is the simple field copy: notes → description,
 * and `completeBy` becomes a start-of-day instant in [timeZone] (mirroring the Brain dump overlay's
 * `DraftTask.toCreatePayload`).
 */
internal fun BrainDumpDraft.toCreatePayload(timeZone: String): CreateItem.Payload {
    val zone = runCatching { TimeZone.of(timeZone) }.getOrDefault(TimeZone.UTC)
    return CreateItem.Payload.Task(
        CreateTaskPayload(
            title = title.trim(),
            description = notes?.ifBlank { null },
            completeBy = completeBy?.atStartOfDayIn(zone)?.toString(),
            deadlineTimeOfDay = deadlineTimeOfDay?.toString(),
        ),
    )
}

/** No-op Calendar read source — the shell's test default when no Account session supplies one (#74). */
internal val NoopCalendarRepository = object : CalendarRepository {
    override fun observeMarkers(from: LocalDate, to: LocalDate) =
        MutableStateFlow<Map<LocalDate, Int>>(emptyMap())

    override fun observeDay(date: LocalDate) = MutableStateFlow<List<CalendarItem>>(emptyList())
    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/** No-op occurrence-act seam — the shell's test default (a real one is command-backed, #74). */
internal val NoopOccurrenceEditor = object : OccurrenceEditor {
    override suspend fun mark(itemId: String, action: OccurrenceAction) {}
    override suspend fun clear(itemId: String) {}
    override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
}

/**
 * Inert Assistant client (ADR-0040) — the shell's default when no host wires the real one: every call is
 * Unavailable, so the availability gate stays `entitled = false` and the Assistant Destination stays absent.
 * Used by the Android/desktop hosts (their Assistant Views are deferred) and the shell component tests; only
 * the iOS host passes the real client, so the Destination appears only there in v1.
 */
internal val InertAssistantClient = object : AssistantClient {
    override suspend fun availability(orgId: OrgId) = RemoteSnapshot.Unavailable
    override suspend fun setEnablement(orgId: OrgId, enabled: Boolean) = RemoteSnapshot.Unavailable
    override suspend fun apply(orgId: OrgId, proposal: AssistantProposal) = RemoteSnapshot.Unavailable
    override suspend fun conversations(orgId: OrgId) = RemoteSnapshot.Unavailable
    override suspend fun conversation(orgId: OrgId, id: ConversationId) = RemoteSnapshot.Unavailable
}

/** Inert Conversation cache (ADR-0040) — the shell's default when no Account session supplies one: empty,
 *  read-only. Paired with [InertAssistantClient] so a never-shown Assistant Destination needs no real store. */
internal val InertConversationStore = object : ConversationStore {
    override fun observeConversations() = flowOf(emptyList<Conversation>())
    override fun observeMessages(id: ConversationId) = flowOf(emptyList<ChatMessage>())
    override suspend fun upsertConversation(conversation: Conversation) {}
    override suspend fun upsertMessage(conversationId: ConversationId, message: ChatMessage) {}
    override suspend fun upsertMessages(conversationId: ConversationId, messages: List<ChatMessage>) {}
}

/** No-op feedback service — the shell's test default when no AppComponent supplies one (#375). */
internal val NoopFeedbackRepository = object : FeedbackRepository {
    override suspend fun submit(draft: com.circuitstitch.deferno.core.data.feedback.FeedbackDraft): FeedbackResult =
        FeedbackResult.Offline
}

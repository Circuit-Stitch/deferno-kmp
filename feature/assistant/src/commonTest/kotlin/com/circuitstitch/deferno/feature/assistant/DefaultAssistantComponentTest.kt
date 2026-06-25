package com.circuitstitch.deferno.feature.assistant

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.AssistantAvailability
import com.circuitstitch.deferno.core.model.AssistantProposal
import com.circuitstitch.deferno.core.model.ChatMessage
import com.circuitstitch.deferno.core.model.ChatRole
import com.circuitstitch.deferno.core.model.Conversation
import com.circuitstitch.deferno.core.model.ConversationDetail
import com.circuitstitch.deferno.core.model.ConversationId
import com.circuitstitch.deferno.core.model.OrgId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Behaviour of [DefaultAssistantComponent] (#282, ADR-0040) driven through its public `StateFlow` with all
 * three seams faked (no real network, SSE, or SQLite). State is asserted via `state.value` after
 * `advanceUntilIdle()` settles the `StandardTestDispatcher` — robust across the many async hops of a turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAssistantComponentTest {

    private val available = AssistantAvailability(entitled = true, enabled = true)

    // --- streaming turn ---

    @Test
    fun aStreamedReplyAccruesTokenByTokenAndFlipsStreamingOff() = runTest {
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.TextDelta("Hel"), AssistantEvent.TextDelta("lo"), AssistantEvent.Done)
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()

        component.onComposerChanged("hi there")
        component.onSend()
        advanceUntilIdle()

        val s = component.state.value
        assertEquals(listOf("hi there", "Hello"), s.messages.map { it.text })
        assertEquals(listOf(ChatRole.User, ChatRole.Assistant), s.messages.map { it.role })
        assertFalse(s.streaming)
        assertEquals("", s.composer)
        // The turn carried a (client-minted) conversation id.
        assertEquals(1, stream.requests.size)
    }

    @Test
    fun eachDeltaIsPersistedAsItStreams() = runTest {
        val store = FakeConversationStore()
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.TextDelta("a"), AssistantEvent.TextDelta("b"), AssistantEvent.Done)
        }
        val component = assistantComponent(store = store, stream = stream)
        advanceUntilIdle()

        component.onComposerChanged("go")
        component.onSend()
        advanceUntilIdle()

        // user message + an upsert per delta (accruing "a" then "ab") — persisted live, not just at the end.
        val assistantUpserts = store.upserts.filter { it.second.role == ChatRole.Assistant }
        assertEquals(listOf("a", "ab"), assistantUpserts.map { it.second.text })
    }

    // --- proposals ---

    @Test
    fun aProposalEventSurfacesAsAnInlineCard() = runTest {
        val proposal = AssistantProposal(tool = "delete_items", input = "{}", summary = "Delete 8 tasks")
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.Proposal(proposal), AssistantEvent.Done)
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("clean up")
        component.onSend()
        advanceUntilIdle()

        assertEquals(proposal, component.state.value.pendingProposal)
    }

    @Test
    fun confirmingAProposalAppliesServerSideAndTriggersResync() = runTest {
        var resynced = false
        val client = FakeAssistantClient().apply { applyResult = RemoteSnapshot.Available(true) }
        val proposal = AssistantProposal(tool = "delete_items", input = "{}", summary = "Delete 8 tasks")
        val stream = FakeAssistantStream().apply { script(AssistantEvent.Proposal(proposal), AssistantEvent.Done) }
        val component = assistantComponent(client = client, stream = stream, resync = { resynced = true })
        advanceUntilIdle()
        component.onComposerChanged("clean up"); component.onSend(); advanceUntilIdle()

        component.onConfirmProposal()
        advanceUntilIdle()

        assertEquals(listOf(proposal), client.appliedProposals)
        assertTrue(resynced, "an applied change re-syncs the affected items")
        assertNull(component.state.value.pendingProposal)
        // The outcome is recorded in the transcript.
        assertTrue(component.state.value.messages.any { it.text == "Applied: Delete 8 tasks" })
    }

    @Test
    fun rejectingAProposalDiscardsItClientSideWithNoServerCall() = runTest {
        val client = FakeAssistantClient()
        val proposal = AssistantProposal(tool = "delete_items", input = "{}", summary = "Delete 8 tasks")
        val stream = FakeAssistantStream().apply { script(AssistantEvent.Proposal(proposal), AssistantEvent.Done) }
        val component = assistantComponent(client = client, stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("clean up"); component.onSend(); advanceUntilIdle()

        component.onRejectProposal()
        advanceUntilIdle()

        assertNull(component.state.value.pendingProposal)
        assertTrue(client.appliedProposals.isEmpty(), "reject never calls apply")
    }

    // --- availability gating + enable flow ---

    @Test
    fun anUnentitledOrgIsNeitherAvailableNorEnableable() = runTest {
        val client = FakeAssistantClient().apply {
            availability = RemoteSnapshot.Available(AssistantAvailability(entitled = false, enabled = false))
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        val s = component.state.value
        assertFalse(s.available)
        assertFalse(s.needsEnable)
    }

    @Test
    fun anEntitledButNotEnabledOrgShowsTheEnableCtaWithDisclosure() = runTest {
        val client = FakeAssistantClient().apply {
            availability = RemoteSnapshot.Available(AssistantAvailability(entitled = true, enabled = false, disclosure = "we egress your data"))
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        val s = component.state.value
        assertTrue(s.needsEnable)
        assertFalse(s.available)
        assertEquals("we egress your data", s.disclosure)
    }

    @Test
    fun enablingShowsTheDisclosureThenConsentEnablesIt() = runTest {
        val client = FakeAssistantClient().apply {
            availability = RemoteSnapshot.Available(AssistantAvailability(entitled = true, enabled = false, disclosure = "consent"))
            enablementResult = RemoteSnapshot.Available(AssistantAvailability(entitled = true, enabled = true, disclosure = "consent"))
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        component.onEnableRequested()
        assertTrue(component.state.value.showingDisclosure)

        component.onConsentAccepted()
        advanceUntilIdle()

        assertEquals(listOf(true), client.enablementCalls)
        val s = component.state.value
        assertTrue(s.available)
        assertFalse(s.showingDisclosure)
    }

    @Test
    fun disablingWithdrawsConsentImmediately() = runTest {
        val client = FakeAssistantClient().apply {
            enablementResult = RemoteSnapshot.Available(AssistantAvailability(entitled = true, enabled = false))
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        component.onDisable()
        advanceUntilIdle()

        assertEquals(listOf(false), client.enablementCalls)
        assertFalse(component.state.value.available)
    }

    // --- offline / exhaustion / errors / cancel ---

    @Test
    fun theComposerIsDisabledOffline() = runTest {
        val connectivity = FakeConnectivity(initial = false)
        val component = assistantComponent(connectivity = connectivity)
        advanceUntilIdle()

        component.onComposerChanged("hi")
        assertFalse(component.state.value.online)
        assertFalse(component.state.value.canSend)
    }

    @Test
    fun anExhaustedTokenPoolHardStopsTheComposer() = runTest {
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.TextDelta("x"), AssistantEvent.Usage(exhausted = true), AssistantEvent.Done)
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("go"); component.onSend(); advanceUntilIdle()

        component.onComposerChanged("again")
        val s = component.state.value
        assertTrue(s.usageExhausted)
        assertFalse(s.canSend)
    }

    @Test
    fun aMidStreamErrorSurfacesGracefullyWithoutBreakingTheChat() = runTest {
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.TextDelta("partial"), AssistantEvent.Error("connection dropped"))
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("go"); component.onSend(); advanceUntilIdle()

        val s = component.state.value
        assertEquals("connection dropped", s.error)
        assertFalse(s.streaming)
        // The partial reply is preserved (never waste the work).
        assertTrue(s.messages.any { it.text == "partial" })
    }

    @Test
    fun cancellingAnInFlightTurnStopsStreaming() = runTest {
        val stream = FakeAssistantStream().apply {
            script(AssistantEvent.TextDelta("working"), thenSuspend = true)
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("go"); component.onSend(); advanceUntilIdle()
        assertTrue(component.state.value.streaming, "the turn is parked mid-stream")

        component.onCancelTurn()
        advanceUntilIdle()

        assertFalse(component.state.value.streaming)
    }

    // --- switcher + cross-device hydration ---

    @Test
    fun serverListedConversationsAppearInTheSwitcher() = runTest {
        val client = FakeAssistantClient().apply {
            conversationsResult = RemoteSnapshot.Available(listOf(ConversationId("c-web")))
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        assertTrue(component.state.value.conversations.any { it.id == ConversationId("c-web") })
    }

    @Test
    fun selectingAConversationHydratesItsMessagesFromTheServer() = runTest {
        val id = ConversationId("c-web")
        val client = FakeAssistantClient().apply {
            conversationDetail = { cid ->
                RemoteSnapshot.Available(
                    ConversationDetail(
                        conversation = Conversation(cid, "Cleanup", Instant.parse("2026-06-24T10:00:00Z")),
                        messages = listOf(ChatMessage("m1", ChatRole.Assistant, "started on web", Instant.parse("2026-06-24T10:00:00Z"))),
                    ),
                )
            }
        }
        val component = assistantComponent(client = client)
        advanceUntilIdle()

        component.onSelectConversation(id)
        advanceUntilIdle()

        assertEquals(id, component.state.value.activeConversationId)
        assertTrue(component.state.value.messages.any { it.text == "started on web" })
    }

    // --- harness ---

    private var idCounter = 0
    private var clockSeconds = 0L

    private fun TestScope.assistantComponent(
        client: FakeAssistantClient = FakeAssistantClient().apply { availability = RemoteSnapshot.Available(available) },
        store: FakeConversationStore = FakeConversationStore(),
        stream: FakeAssistantStream = FakeAssistantStream(),
        connectivity: FakeConnectivity = FakeConnectivity(initial = true),
        resync: suspend () -> Unit = {},
    ) = DefaultAssistantComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        orgId = OrgId("org-1"),
        client = client,
        store = store,
        stream = stream,
        connectivity = connectivity,
        resyncAfterApply = resync,
        newId = { "id-${idCounter++}" },
        now = { Instant.fromEpochSeconds(1_750_000_000L + clockSeconds++) },
        coroutineContext = StandardTestDispatcher(testScheduler),
    )
}

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
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun eachToolCallSurfacesAsTransientActivityClearedOnTheNextTurn() = runTest {
        val stream = FakeAssistantStream().apply {
            script(
                AssistantEvent.ToolCall(tool = "list_items", input = ""),
                AssistantEvent.TextDelta("Here's a plan"),
                AssistantEvent.Done,
            )
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("organize my issues")
        component.onSend()
        advanceUntilIdle()
        assertEquals(listOf("list_items"), component.state.value.actions)

        // A fresh conversation drops the prior turn's activity (transient, not part of the transcript).
        component.onNewConversation()
        assertEquals(emptyList(), component.state.value.actions)
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
        val component = assistantComponent(
            enablement = FakeEnablement(MutableStateFlow(AssistantAvailability(entitled = false, enabled = false))),
        )
        advanceUntilIdle()

        val s = component.state.value
        assertFalse(s.available)
        assertFalse(s.needsEnable)
    }

    @Test
    fun anEntitledButNotEnabledOrgShowsTheEnableCtaWithDisclosure() = runTest {
        val component = assistantComponent(
            enablement = FakeEnablement(
                MutableStateFlow(AssistantAvailability(entitled = true, enabled = false, disclosure = "we egress your data")),
            ),
        )
        advanceUntilIdle()

        val s = component.state.value
        assertTrue(s.needsEnable)
        assertFalse(s.available)
        assertEquals("we egress your data", s.disclosure)
    }

    @Test
    fun enablingShowsTheDisclosureThenConsentEnablesIt() = runTest {
        val enablement = FakeEnablement(
            MutableStateFlow(AssistantAvailability(entitled = true, enabled = false, disclosure = "consent")),
        )
        val component = assistantComponent(enablement = enablement)
        advanceUntilIdle()

        component.onEnableRequested()
        assertTrue(component.state.value.showingDisclosure)

        component.onConsentAccepted()
        advanceUntilIdle()

        assertEquals(listOf(true), enablement.calls)
        val s = component.state.value
        assertTrue(s.available, "the shared gate, flipped through the write-through, makes the chat available")
        assertFalse(s.showingDisclosure)
    }

    @Test
    fun aSurfaceFlipReflectsThroughTheSharedAvailabilitySource() = runTest {
        // The Destination + the Settings row observe the SAME availability flow, so a flip from either is seen
        // by both (no within-session divergence). Here a Settings-side disable lands on the chat's gate.
        val enablement = FakeEnablement(MutableStateFlow(AssistantAvailability(entitled = true, enabled = true)))
        val component = assistantComponent(enablement = enablement)
        advanceUntilIdle()
        assertTrue(component.state.value.available)

        enablement.availability.value = AssistantAvailability(entitled = true, enabled = false)
        advanceUntilIdle()

        assertFalse(component.state.value.available, "the chat reflects an enablement change made elsewhere")
        assertTrue(component.state.value.needsEnable)
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
    fun aUsageResetClearsTheExhaustedHardStop() = runTest {
        // The hard-stop is symmetric: a later non-exhausted usage frame (e.g. after the monthly reset) clears
        // it, rather than sticking until the app restarts.
        val stream = FakeAssistantStream().apply {
            script(
                AssistantEvent.Usage(exhausted = true),
                AssistantEvent.Usage(exhausted = false),
                AssistantEvent.Done,
            )
        }
        val component = assistantComponent(stream = stream)
        advanceUntilIdle()
        component.onComposerChanged("go"); component.onSend(); advanceUntilIdle()

        assertFalse(component.state.value.usageExhausted, "a non-exhausted usage frame clears the hard-stop")
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
        assertEquals(AssistantError.ServerMessage("connection dropped"), s.errorKind)
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
        client: FakeAssistantClient = FakeAssistantClient(),
        store: FakeConversationStore = FakeConversationStore(),
        stream: FakeAssistantStream = FakeAssistantStream(),
        connectivity: FakeConnectivity = FakeConnectivity(initial = true),
        // The shared availability source + enablement write-through the shell owns (defaults to entitled+enabled).
        enablement: FakeEnablement = FakeEnablement(),
        resync: suspend () -> Unit = {},
    ) = DefaultAssistantComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        orgId = OrgId("org-1"),
        client = client,
        store = store,
        stream = stream,
        connectivity = connectivity,
        availability = enablement.availability,
        setEnabled = enablement::setEnabled,
        resyncAfterApply = resync,
        newId = { "id-${idCounter++}" },
        now = { Instant.fromEpochSeconds(1_750_000_000L + clockSeconds++) },
        coroutineContext = StandardTestDispatcher(testScheduler),
    )
}

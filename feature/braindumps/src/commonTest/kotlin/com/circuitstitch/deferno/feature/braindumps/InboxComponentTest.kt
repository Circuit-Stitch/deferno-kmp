package com.circuitstitch.deferno.feature.braindumps

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val CREATED = Instant.parse("2026-06-14T09:00:00Z")

private fun draft(id: String, status: BrainDumpDraftStatus = BrainDumpDraftStatus.Ready) =
    BrainDumpDraft(id = BrainDumpDraftId(id), title = "Draft $id", status = status, createdAt = CREATED)

/**
 * An in-memory draft store standing in for the shell's `brainDumpDraftRepository` + create seam: the
 * accept seam mimics the shell (mark Accepted on success, which leaves the Ready list), and [upsert]
 * replaces a draft by id so dismiss/undo reflect live (same-driver behaviour).
 */
private class FakeDraftStore(initial: List<BrainDumpDraft>) {
    val drafts = MutableStateFlow(initial)
    var acceptResult: AcceptResult = AcceptResult.Accepted
    val acceptedIds = mutableListOf<BrainDumpDraftId>()

    fun observe(): Flow<List<BrainDumpDraft>> = drafts

    suspend fun accept(d: BrainDumpDraft): AcceptResult {
        if (acceptResult == AcceptResult.Accepted) {
            acceptedIds += d.id
            upsert(d.copy(status = BrainDumpDraftStatus.Accepted))
        }
        return acceptResult
    }

    suspend fun upsert(d: BrainDumpDraft) {
        drafts.value = drafts.value.map { if (it.id == d.id) d else it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxComponentTest {

    private fun TestScope.inbox(store: FakeDraftStore) = DefaultInboxComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        observeDrafts = store::observe,
        accept = store::accept,
        upsert = store::upsert,
        // Unconfined so state resolves eagerly/synchronously — the repo's pattern for component tests
        // that read the settled state after each action (no awaiting intermediate emissions).
        coroutineContext = UnconfinedTestDispatcher(testScheduler),
    )

    /** Collect [InboxComponent.state] into a list for the duration of the test (WhileSubscribed needs a live collector). */
    private fun TestScope.observe(component: InboxComponent): List<InboxState> {
        val states = mutableListOf<InboxState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { component.state.collect { states += it } }
        return states
    }

    @Test
    fun stateShowsOnlyReadyDrafts() = runTest {
        val store = FakeDraftStore(
            listOf(
                draft("a"),
                draft("b", BrainDumpDraftStatus.Accepted),
                draft("c", BrainDumpDraftStatus.Dismissed),
            ),
        )
        val states = observe(inbox(store))
        advanceUntilIdle()

        assertEquals(listOf(BrainDumpDraftId("a")), states.last().rows.map { it.draft.id })
    }

    @Test
    fun acceptCommitsAndRemovesFromQueue() = runTest {
        val store = FakeDraftStore(listOf(draft("a"), draft("b")))
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onAccept(BrainDumpDraftId("a"))
        advanceUntilIdle()

        assertEquals(listOf(BrainDumpDraftId("a")), store.acceptedIds)
        assertEquals(listOf(BrainDumpDraftId("b")), states.last().rows.map { it.draft.id })
    }

    @Test
    fun acceptOfflineKeepsTheDraftWithAReconnectNote() = runTest {
        val store = FakeDraftStore(listOf(draft("a"))).apply { acceptResult = AcceptResult.Offline }
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onAccept(BrainDumpDraftId("a"))
        advanceUntilIdle()

        val row = states.last().rows.single()
        assertEquals(BrainDumpDraftId("a"), row.draft.id)
        assertEquals(InboxNote.Offline, row.noteKind)
        assertEquals(false, row.accepting)
    }

    @Test
    fun acceptFailedKeepsTheDraftWithTheServerMessage() = runTest {
        val store = FakeDraftStore(listOf(draft("a"))).apply { acceptResult = AcceptResult.Failed("Server said no") }
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onAccept(BrainDumpDraftId("a"))
        advanceUntilIdle()

        assertEquals(InboxNote.ServerMessage("Server said no"), states.last().rows.single().noteKind)
    }

    @Test
    fun dismissRemovesFromQueueAndOffersUndo() = runTest {
        val store = FakeDraftStore(listOf(draft("a"), draft("b")))
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onDismiss(BrainDumpDraftId("a"))
        advanceUntilIdle()

        assertEquals(listOf(BrainDumpDraftId("b")), states.last().rows.map { it.draft.id })
        assertEquals(BrainDumpDraftId("a"), states.last().recentlyDismissed?.id)
    }

    @Test
    fun undoRestoresTheDismissedDraft() = runTest {
        val store = FakeDraftStore(listOf(draft("a")))
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onDismiss(BrainDumpDraftId("a"))
        advanceUntilIdle()
        assertTrue(states.last().rows.isEmpty())

        component.onUndoDismiss()
        advanceUntilIdle()

        assertEquals(listOf(BrainDumpDraftId("a")), states.last().rows.map { it.draft.id })
        assertNull(states.last().recentlyDismissed)
    }

    @Test
    fun clearNoteRemovesTheGentleNote() = runTest {
        val store = FakeDraftStore(listOf(draft("a"))).apply { acceptResult = AcceptResult.Offline }
        val component = inbox(store)
        val states = observe(component)
        advanceUntilIdle()

        component.onAccept(BrainDumpDraftId("a"))
        advanceUntilIdle()
        assertEquals(InboxNote.Offline, states.last().rows.single().noteKind)

        component.onClearNote(BrainDumpDraftId("a"))
        advanceUntilIdle()
        assertNull(states.last().rows.single().noteKind)
    }

    @Test
    fun reQueriesOnResume_toSeeWorkerWritesFromAnotherDriver() = runTest {
        // A COLD flow that snapshots `snapshot` at subscribe time (no live updates) — models the brain-dump
        // worker's SEPARATE SQLDelight driver: the UI flow can't see its writes until a fresh re-subscription
        // on resume (the documented Stage-2 cross-driver constraint).
        val snapshot = mutableListOf(draft("a"))
        var observeCalls = 0
        val lifecycle = LifecycleRegistry()
        val component = DefaultInboxComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeDrafts = { observeCalls++; flowOf(snapshot.toList()) },
            accept = { AcceptResult.Accepted },
            upsert = {},
            coroutineContext = UnconfinedTestDispatcher(testScheduler),
        )
        val states = observe(component)
        advanceUntilIdle()
        assertEquals(listOf(BrainDumpDraftId("a")), states.last().rows.map { it.draft.id })

        // The worker persists a new draft via its own driver — the cold UI flow doesn't see it live.
        snapshot.add(draft("b"))
        advanceUntilIdle()
        assertEquals(
            listOf(BrainDumpDraftId("a")),
            states.last().rows.map { it.draft.id },
            "no live cross-driver update before resume",
        )

        // Resuming the Destination re-queries (a fresh observeDrafts/selectAll) — now it sees the worker's write.
        lifecycle.resume()
        advanceUntilIdle()
        assertEquals(
            listOf(BrainDumpDraftId("a"), BrainDumpDraftId("b")),
            states.last().rows.map { it.draft.id },
        )
        assertTrue(observeCalls >= 2, "resume forced a fresh observeDrafts() subscription")
    }
}

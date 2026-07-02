package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.agent.ImpedimentClass
import com.circuitstitch.deferno.core.agent.ImpedimentClassification
import com.circuitstitch.deferno.core.agent.ImpedimentClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The deterministic **Breakdown** state machine (Deferno#525) — the Kotlin port of the iOS-native engine,
 * driving the impediment conversation for the Compose surface: *"what's stopping you?"* → classify the
 * answer → route to one concrete move → recurse into any new parts → stop when everything is Ready. The
 * model is consulted at **exactly one point** ([ImpedimentClassifier.classify]); move selection, recursion,
 * and the terminal check are LLM-free, so the whole flow is unit-tested with a scripted classifier + a spy
 * [BreakdownActions].
 *
 * State is exposed as [StateFlow]s the Compose View collects ([messages] / [phase] / [focusTitle]); each
 * intent is a `suspend` the View launches, applied inline (no fire-and-forget), so every transition is
 * deterministic and tests just `await` them. The moves it applies are the shared offline-first
 * [BreakdownActions] (capture child / drop / add-to-plan), so a captured subtask or a drop rides the same
 * outbox as any other edit (#185) — the engine never reimplements persistence.
 *
 * The engine holds **no user-visible prose**: a [Message] is a typed [MessageKind] + its interpolation
 * args, and the View resolves each kind to a localized `breakdown_msg_*` string resource at render time.
 * The one exception is the two fallback [Prerequisites] titles, which are *persisted* as server-synced
 * subtasks — those are injected localized at construction (defaults keep the English wiring unchanged).
 */
class BreakdownEngine(
    root: ItemContext,
    private val classifier: ImpedimentClassifier,
    private val moves: BreakdownActions,
    private val prerequisites: Prerequisites = Prerequisites(),
) {
    enum class Role { Assistant, User }

    /**
     * What a conversation line *says* — the View maps each kind to its localized `breakdown_msg_*` resource,
     * so no English is baked into engine state. [Message.arg]/[Message.args] carry the interpolations.
     */
    enum class MessageKind {
        /** The person's own words ([Message.arg]) — user content, rendered verbatim, never localized. */
        UserText,

        /** "What's stopping you from …?" — [Message.arg] is the focus title. */
        WhatsStopping,

        /** Subtasks captured for a too-big item — [Message.args] are the created titles, listed by the View. */
        BrokeInto,

        /** Too-big came back with no usable titles — ask the person to name the first small piece. */
        NameFirstPiece,

        /** A prerequisite subtask was captured — [Message.arg] is its title. */
        AddedPrerequisite,

        /** The drop offer (PRD #14/#26) — [Message.arg] is the node title. */
        ConfirmDrop,

        /** The confirmed drop happened — [Message.arg] is the dropped title. */
        Dropped,

        /** The drop was declined; re-asking — [Message.arg] is the kept title. */
        KeptReask,

        /** A transient obstacle — skip it for now, no structural change. */
        TransientObstacle,

        /** Something more urgent — degraded acknowledgement until the priority model (#526) lands. */
        MoreUrgent,

        /** Waiting on a dependency — degraded acknowledgement until set_blocked_by lands. */
        WaitingOnDependency,

        /** Nothing stopping — [Message.arg] is the ready title. */
        ReadyToGo,

        /** The Ready terminal's confirmed add-to-plan (PRD #21). */
        AddedToPlan,

        /** A transient classifier hiccup — invite a rephrase. */
        ClassifierRetry,

        /** Terminal: the root was dropped. */
        FinishedDropped,

        /** Terminal: everything broken down and Ready. */
        FinishedReady,
    }

    /**
     * One line in the Breakdown conversation the View renders; [id] is a stable per-engine sequence (Compose
     * key). [kind] says *what* the line is; [arg] (a task/node title, or the person's own words for
     * [MessageKind.UserText]) and [args] (the created subtask titles for [MessageKind.BrokeInto]) are its data.
     */
    data class Message(
        val id: Long,
        val role: Role,
        val kind: MessageKind,
        val arg: String? = null,
        val args: List<String> = emptyList(),
    )

    /**
     * The two fallback prerequisite titles the engine may **persist** as server-synced subtasks. Injected —
     * mirroring `BrainDumpPipeline`'s salvage prose — so each platform passes localized words at engine
     * creation time; the defaults keep the English wiring working unchanged.
     */
    data class Prerequisites(
        val figureOutHow: String = "Figure out how to do this",
        val defineDone: String = "Define what “done” looks like, and decide if I'm the right person or should delegate",
    )

    sealed interface Phase {
        /** Waiting for the person's free-text answer to "what's stopping you?". */
        data object Asking : Phase

        /** Classifying / applying a move — input disabled. */
        data object Working : Phase

        /** Proposed the one destructive move (a drop); waiting for an explicit yes/no (PRD #26). */
        data object ConfirmingDrop : Phase

        /** Terminal: every leaf Ready / dropped / bailed out. */
        data class Finished(val outcome: Outcome) : Phase
    }

    enum class Outcome { Ready, Dropped, Bailed }

    /** The root item under the lens — its [title] + [notes] (body) are the classifier's item context. */
    data class ItemContext(val id: String, val title: String, val notes: String? = null)

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _phase = MutableStateFlow<Phase>(Phase.Working)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The title currently in focus — the View's header ("Breaking down: …"); `null` at the terminal. */
    private val _focusTitle = MutableStateFlow<String?>(null)
    val focusTitle: StateFlow<String?> = _focusTitle.asStateFlow()

    private data class Node(val id: String, val title: String)

    private val rootId = root.id
    private val rootNotes = root.notes

    /** Depth-first worklist of parts still to break down; [current] is the focus we're asking about. */
    private val stack = ArrayDeque<Node>()
    private var current: Node? = null
    private var pendingDrop: Node? = null
    private var rootDropped = false
    private var nextId = 0L

    init {
        ask(Node(root.id, root.title))
    }

    // ----- Intents -----

    /** The person answered "what's stopping you?". Classify it and apply the routed move. */
    suspend fun submit(answer: String) {
        val text = answer.trim()
        val node = current
        if (_phase.value != Phase.Asking || node == null || text.isEmpty()) return
        append(Role.User, MessageKind.UserText, arg = text)
        _phase.value = Phase.Working

        val classification = try {
            classifier.classify(text, node.title, notesFor(node))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transient on-device hiccup: never strand the person — let them rephrase (availability is
            // checked before the engine is built, so this is a generation glitch, not "no engine").
            append(Role.Assistant, MessageKind.ClassifierRetry)
            current = node
            _phase.value = Phase.Asking
            return
        }
        route(classification, node)
    }

    /** Answer the drop offer (PRD #14/#26): an explicit yes really drops; no keeps it and re-asks. */
    suspend fun confirmDrop(yes: Boolean) {
        if (_phase.value != Phase.ConfirmingDrop) return
        val node = pendingDrop ?: return
        pendingDrop = null
        _phase.value = Phase.Working
        if (yes) {
            moves.drop(node.id)
            if (node.id == rootId) rootDropped = true
            append(Role.Assistant, MessageKind.Dropped, arg = node.title)
            advance()
        } else {
            append(Role.Assistant, MessageKind.KeptReask, arg = node.title)
            current = node
            _focusTitle.value = node.title
            _phase.value = Phase.Asking
        }
    }

    /** The Ready terminal's "Add to plan" affordance (PRD #21): a confirmed, non-destructive move. */
    suspend fun addRootToPlan() {
        val p = _phase.value
        if (p !is Phase.Finished || p.outcome != Outcome.Ready) return
        moves.addToPlan(rootId)
        append(Role.Assistant, MessageKind.AddedToPlan)
    }

    /** Leave at any point (PRD #22) — never trapped in a long interrogation. */
    fun bail() {
        if (_phase.value is Phase.Finished) return
        _phase.value = Phase.Finished(Outcome.Bailed)
    }

    // ----- Routing (deterministic, LLM-free) -----

    private suspend fun route(c: ImpedimentClassification, node: Node) {
        when (c.kind) {
            ImpedimentClass.tooBig -> {
                val created = capture(c.subtaskTitles, node)
                if (created.isEmpty()) {
                    append(Role.Assistant, MessageKind.NameFirstPiece)
                    current = node
                    _phase.value = Phase.Asking
                    return
                }
                append(Role.Assistant, MessageKind.BrokeInto, args = created.map { it.title })
                created.asReversed().forEach { stack.addLast(it) } // depth-first into the new parts
                advance()
            }

            ImpedimentClass.dontKnowHow, ImpedimentClass.scaredOfDoingItWrong -> {
                val title = c.prerequisiteTitle?.trim()?.takeIf { it.isNotEmpty() } ?: defaultPrerequisite(c.kind)
                val id = moves.captureSubtask(node.id, title)
                if (id != null) {
                    append(Role.Assistant, MessageKind.AddedPrerequisite, arg = title)
                    stack.addLast(Node(id, title))
                }
                advance()
            }

            ImpedimentClass.persistentAvoidance -> {
                pendingDrop = node
                append(Role.Assistant, MessageKind.ConfirmDrop, arg = node.title)
                _phase.value = Phase.ConfirmingDrop
            }

            ImpedimentClass.transientObstacle ->
                advanceWith(MessageKind.TransientObstacle)

            // Degraded until the priority model (#526) lands — record the tension, don't bury the item.
            ImpedimentClass.somethingMoreUrgent ->
                advanceWith(MessageKind.MoreUrgent)

            // Degraded until set_blocked_by lands — acknowledge, don't fake an edge.
            ImpedimentClass.waitingOnDependency ->
                advanceWith(MessageKind.WaitingOnDependency)

            ImpedimentClass.nothingStopping ->
                advanceWith(MessageKind.ReadyToGo, arg = node.title)
        }
    }

    private suspend fun capture(titles: List<String>, parent: Node): List<Node> {
        val created = mutableListOf<Node>()
        for (raw in titles) {
            val title = raw.trim()
            if (title.isEmpty()) continue
            val id = moves.captureSubtask(parent.id, title)
            if (id != null) created.add(Node(id, title))
        }
        return created
    }

    // ----- Worklist -----

    private fun ask(node: Node) {
        current = node
        _focusTitle.value = node.title
        append(Role.Assistant, MessageKind.WhatsStopping, arg = node.title)
        _phase.value = Phase.Asking
    }

    /** Say [kind] (with [arg]), then move on to the next part (or the terminal) — the common "no further input" tail. */
    private fun advanceWith(kind: MessageKind, arg: String? = null) {
        append(Role.Assistant, kind, arg = arg)
        advance()
    }

    private fun advance() {
        val next = stack.removeLastOrNull()
        if (next != null) {
            ask(next)
        } else {
            current = null
            _focusTitle.value = null
            val outcome = if (rootDropped) Outcome.Dropped else Outcome.Ready
            append(
                Role.Assistant,
                if (outcome == Outcome.Dropped) MessageKind.FinishedDropped else MessageKind.FinishedReady,
            )
            _phase.value = Phase.Finished(outcome)
        }
    }

    // ----- Helpers -----

    private fun notesFor(node: Node): String? = if (node.id == rootId) rootNotes else null

    private fun append(role: Role, kind: MessageKind, arg: String? = null, args: List<String> = emptyList()) {
        _messages.value = _messages.value + Message(nextId++, role, kind, arg, args)
    }

    private fun defaultPrerequisite(kind: ImpedimentClass): String = when (kind) {
        ImpedimentClass.scaredOfDoingItWrong -> prerequisites.defineDone
        else -> prerequisites.figureOutHow
    }
}

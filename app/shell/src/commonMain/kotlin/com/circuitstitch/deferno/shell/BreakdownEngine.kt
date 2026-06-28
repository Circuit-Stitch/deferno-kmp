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
 */
class BreakdownEngine(
    root: ItemContext,
    private val classifier: ImpedimentClassifier,
    private val moves: BreakdownActions,
) {
    enum class Role { Assistant, User }

    /** One line in the Breakdown conversation the View renders; [id] is a stable per-engine sequence (Compose key). */
    data class Message(val id: Long, val role: Role, val text: String)

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
        append(Role.User, text)
        _phase.value = Phase.Working

        val classification = try {
            classifier.classify(text, node.title, notesFor(node))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A transient on-device hiccup: never strand the person — let them rephrase (availability is
            // checked before the engine is built, so this is a generation glitch, not "no engine").
            append(Role.Assistant, "I couldn't quite work that out on-device. Want to say it a different way?")
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
            append(Role.Assistant, "Done — taking “${node.title}” off your list. You can recover it from history.")
            advance()
        } else {
            append(Role.Assistant, "Kept it. So — what's stopping you from “${node.title}”?")
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
        append(Role.Assistant, "Added to today's plan.")
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
                    append(Role.Assistant, "Let's name the first small piece — what's one part you could start on?")
                    current = node
                    _phase.value = Phase.Asking
                    return
                }
                append(Role.Assistant, "Broke it into " + listed(created.map { it.title }) + ".")
                created.asReversed().forEach { stack.addLast(it) } // depth-first into the new parts
                advance()
            }

            ImpedimentClass.dontKnowHow, ImpedimentClass.scaredOfDoingItWrong -> {
                val title = c.prerequisiteTitle?.trim()?.takeIf { it.isNotEmpty() } ?: defaultPrerequisite(c.kind)
                val id = moves.captureSubtask(node.id, title)
                if (id != null) {
                    append(Role.Assistant, "Added a prerequisite to do first: “$title”.")
                    stack.addLast(Node(id, title))
                }
                advance()
            }

            ImpedimentClass.persistentAvoidance -> {
                pendingDrop = node
                append(
                    Role.Assistant,
                    "Sounds like you keep putting it off. Do you actually need this? I can take " +
                        "“${node.title}” off your list — just say yes.",
                )
                _phase.value = Phase.ConfirmingDrop
            }

            ImpedimentClass.transientObstacle ->
                advanceWith("That's temporary — it's okay to skip it for now. It still mattered to you, so it'll be here when you're ready.")

            // Degraded until the priority model (#526) lands — record the tension, don't bury the item.
            ImpedimentClass.somethingMoreUrgent ->
                advanceWith("Something more pressing right now — noted. (Lowering its priority is coming soon; for now it stays put.)")

            // Degraded until set_blocked_by lands — acknowledge, don't fake an edge.
            ImpedimentClass.waitingOnDependency ->
                advanceWith("Sounds like it's blocked by something else — noted. (Linking that blocker is coming soon.)")

            ImpedimentClass.nothingStopping ->
                advanceWith("Then “${node.title}” is ready to go.")
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
        append(Role.Assistant, "What's stopping you from “${node.title}”?")
        _phase.value = Phase.Asking
    }

    /** Say [text], then move on to the next part (or the terminal) — the common "no further input" tail. */
    private fun advanceWith(text: String) {
        append(Role.Assistant, text)
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
                if (outcome == Outcome.Dropped) {
                    "All set — we cleared what you didn't need."
                } else {
                    "Everything's broken down and ready. Nice work."
                },
            )
            _phase.value = Phase.Finished(outcome)
        }
    }

    // ----- Helpers -----

    private fun notesFor(node: Node): String? = if (node.id == rootId) rootNotes else null

    private fun append(role: Role, text: String) {
        _messages.value = _messages.value + Message(nextId++, role, text)
    }

    private fun listed(titles: List<String>): String = titles.joinToString(", ") { "“$it”" }

    private fun defaultPrerequisite(kind: ImpedimentClass): String = when (kind) {
        ImpedimentClass.scaredOfDoingItWrong ->
            "Define what “done” looks like, and decide if I'm the right person or should delegate"
        else -> "Figure out how to do this"
    }
}

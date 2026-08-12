package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.recipe.Admission
import com.circuitstitch.deferno.core.model.recipe.Clamp
import com.circuitstitch.deferno.core.model.recipe.FiringAdmission
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The **seal witness**: the site that stops compiling when a plugin type is added and not handled.
 *
 * [describe] is that site, and it is the only one. The parity recipe's write direction reads named
 * accessors, so nothing in the recipe layer is exhaustive over [Plugin]: a plugin the writer forgets
 * reaches the round-trip corpus only once `KindShapes` grows an axis for its wire field.
 *
 * [everyWireBackedSampleSurvivesTheWriteDirectionOfSomeKind] is the mechanical guard for that gap. It
 * writes each sample the `when` supplies through the clamp and reads it straight back, so a plugin no
 * kind writes is red here whether or not the corpus knows about its field.
 *
 * ### Why a `when` over plugin types is allowed *here* and nowhere else
 *
 * `Plugin.kt` says not to branch on which plugin is in hand, and `Placement.kt` says the same about
 * validation specifically. That prohibition is about **decisions**: a placement or exclusivity rule
 * expressed as a type cascade type-checks while being wrong, and stays exhaustive when a branch is
 * deleted. This `when` makes no decision — it exists *only* so the compiler counts, and it lives in
 * `commonTest` so no production path can grow a dependency on it. Deleting a branch here is a compile
 * error rather than a silently narrower rule, which is the opposite failure mode.
 *
 * **When adding a Family member: add a branch.** If the compiler sent you here, that is this file
 * working.
 */
class PluginSealTest {

    /** One member of one Family: the Family's name, and a sample instance to put through the wire. */
    private data class Described(val label: String, val sample: Plugin)

    /**
     * Exhaustive over every [Plugin]. No `else` branch, deliberately — an `else` is what would let a
     * new plugin type land unnoticed, and it is the only thing that could break this witness.
     *
     * Each arm answers twice. The **label** is the plugin's Family, which makes this list double as
     * the one readable inventory of the cut. The **sample** is a non-degenerate instance of that
     * member, so a new plugin type cannot land without someone stating a value for it, and every
     * sweep below runs off the samples rather than off a list beside them.
     */
    private fun describe(plugin: Plugin): Described = when (plugin) {
        is Describable -> Described("Content", Describable("at the passport office"))
        is Taggable -> Described("Content", Taggable(listOf("admin")))
        is Attachable -> Described("Content", Attachable(attachmentCount = 2, attachmentTotalSize = 4096))
        is Prioritizable -> Described("Content", Prioritizable(Priority.Fire, pinned = true))
        // `Unanchored` is its family's silence and has no non-degenerate instance. The clamp admits it
        // trivially, which is correct: a plugin that claims nothing cannot be lost.
        is Anchor.Unanchored -> Described("Temporal", Anchor.Unanchored)
        is Anchor.Deadline -> Described("Temporal", Anchor.Deadline(completeBy = deadline))
        is Anchor.Appointment -> Described("Temporal", Anchor.Appointment(start = deadline))
        is Targeted -> Described("Temporal", Targeted(targetDate = target))
        is Repeats -> Described("Unfolding", Repeats(recurrence = Recurrence(Cadence.Daily)))
        is Progress -> Described("Enactment", Progress(Lifecycle.Working(WorkingState.Done)))
        is Trackable -> Described("Enactment", Trackable(productive = 0.75))
        is Outcome -> Described("Enactment", Outcome(OccurrenceResolution.DoneOnTime, doneAt = doneAt))
        is Blocker -> Described("Linkage", Blocker(blocked = true))
        is Succeeds -> Described("Linkage", Succeeds(nextId = "11111111-0000-4000-8000-000000000003"))
        is Importable -> Described("Linkage", Importable(github))
        is Volition -> Described("Modal", Volition(0.5))
        // The five the wire cannot carry (ADR-0057). One per meaning family — five families, five
        // members — sharing only a Reach, which is why they are listed apart rather than merged above.
        is Dynamics -> Described("Unfolding (shadowed)", Dynamics.Telic("passport in hand"))
        is Evaluation -> Described("Enactment (shadowed)", Evaluation(obtained = true))
        is Purpose -> Described("Linkage (shadowed)", Purpose(listOf(Carrot.InWords("go to Japan"))))
        is Obligation -> Described("Modal (shadowed)", Obligation(Force.Must))
        is PersistencePolicy -> Described("Persistence (shadowed)", PersistencePolicy.SkippedIfMissed)
    }

    @Test
    fun everyPluginTypeIsNamedBySomethingThatMustHandleIt() {
        assertEquals("Content", describe(Prioritizable(Priority.Fire)).label)
        assertEquals("Temporal", describe(Anchor.Unanchored).label)
        assertEquals("Unfolding", describe(Repeats()).label)
        assertEquals("Enactment", describe(Progress()).label)
        assertEquals("Enactment", describe(Outcome()).label)
        assertEquals("Linkage", describe(Blocker()).label)
        assertEquals("Modal", describe(Volition()).label)
        assertEquals("Unfolding (shadowed)", describe(Dynamics.Unstated).label)
        assertEquals("Enactment (shadowed)", describe(Evaluation()).label)
        assertEquals("Linkage (shadowed)", describe(Purpose()).label)
        assertEquals("Modal (shadowed)", describe(Obligation()).label)
        assertEquals("Persistence (shadowed)", describe(PersistencePolicy.UntilComplete).label)
    }

    @Test
    fun everyPluginSaysHowFarItsValueCanTravel() {
        // `Plugin.reach` is abstract because neither default is safe: Wire would enqueue a mutation
        // that can never drain, DeviceLocal would silently stop sending a Family that has a field.
        // What this asserts is the split itself — sixteen wire-backed members (fourteen plugin types,
        // of which `Anchor` contributes three) against five shadowed — so a Family that answers
        // wrongly shows up as a count that moved.
        //
        // The two literals are updated deliberately when a member is added, the same convention
        // `KindRecipeRoundTripTest` uses for the corpus counts. They are also the guard that a new
        // type reached [members] and not only the `when`.
        assertEquals(16, samples.count { it.reach == Reach.Wire }, "the wire-backed set changed size")
        assertEquals(5, samples.count { it.reach == Reach.DeviceLocal }, "the shadowed set changed size")
    }

    @Test
    fun everyPluginSaysWhatItsSilenceMeans() {
        // `Plugin.degenerate` is abstract, so a Family that lands without answering does not compile.
        // What this asserts is that the answer is a member of that plugin's OWN family — the mistake
        // an inherited default would let through, and the one that would make the recipe's
        // sparseness rule silently drop the wrong thing.
        for (sample in samples) {
            assertEquals(
                sample.family,
                sample.degenerate.family,
                "${sample::class.simpleName} names a degenerate value from another family",
            )
        }
    }

    @Test
    fun everyWireBackedSampleSurvivesTheWriteDirectionOfSomeKind() {
        // The write direction's guard. `Clamp.admit` writes the set to a kind row, reads it straight
        // back and refuses anything the caller claimed that did not survive — so a plugin the writer
        // forgot is a refusal from all four kinds.
        //
        // "At least one kind", because several members are kind-specific and that is correct:
        // `Volition` and `Attachable` are Task-only, `Anchor.Appointment` is Event-only, `Repeats`
        // never reaches a Task, and a Task has no firings at all.
        //
        // The split is over `scope`, a field, rather than a `when` over types: a plugin belongs to one
        // record, and each record has its own clamp entry point.
        val (onOneDate, onTheDefinition) =
            samples.filter { it.reach == Reach.Wire }.partition { it.scope == Scope.Occurrence }

        val dropped = onTheDefinition.filterNot { sample ->
            ItemKind.entries.any { kind ->
                Clamp.admit(Item(core, listOf(sample)), kind) is Admission.Admitted
            }
        } + onOneDate.filterNot { sample ->
            ItemKind.entries.any { kind ->
                Clamp.admit(firing(sample), kind) is FiringAdmission.Admitted
            }
        }
        assertEquals(emptyList(), dropped, "no kind's write direction kept these plugins")
    }

    @Test
    fun everyShadowedSampleComesBackAsUnsynced() {
        // The other half of the same sweep: a value with no wire field is reported rather than sent
        // or dropped. Both records answer it now — `Evaluation` belongs to a date, so it is admitted
        // as a firing rather than merely found valid there.
        for (sample in samples.filter { it.reach == Reach.DeviceLocal && it.scope == Scope.Definition }) {
            val admitted = assertIs<Admission.Admitted>(
                Clamp.admit(Item(core, listOf(sample)), ItemKind.Task),
                "${sample::class.simpleName} was refused",
            )
            assertEquals(
                listOf(sample),
                admitted.notSynced,
                "${sample::class.simpleName} was not reported as unsynced",
            )
        }
        for (sample in samples.filter { it.reach == Reach.DeviceLocal && it.scope == Scope.Occurrence }) {
            val admitted = assertIs<FiringAdmission.Admitted>(
                Clamp.admit(firing(sample), ItemKind.Chore),
                "${sample::class.simpleName} was refused",
            )
            assertEquals(
                listOf(sample),
                admitted.notSynced,
                "${sample::class.simpleName} was not reported as unsynced",
            )
            assertNull(admitted.fact, "a device-local value alone is not a row the server holds")
        }
    }

    @Test
    fun everyPluginAnswersWhichRecordOwnsIt() {
        // `Plugin.scope` has no default. A plugin that does not answer does not compile, so this
        // asserts the answer is a real one rather than that it exists.
        assertEquals(Scope.Definition, Prioritizable().scope)
    }

    @Test
    fun everyFamilyMarkerIsReachableFromThePluginSeal() {
        // The eight markers are documentation in types: none is ever matched, so nothing else would
        // notice one being dropped. Naming them as TYPES means deleting a family is a compile error
        // here rather than a name in a string list that no longer refers to anything.
        val markers = listOf(
            Content::class, Unfolding::class, Temporal::class, Modal::class,
            Participant::class, Enactment::class, Persistence::class, Linkage::class,
        )
        assertEquals(8, markers.size, "ADR-0055 cuts the model along eight axes")
        assertEquals(markers.size, markers.toSet().size, "a family is named twice")

        // A typed binding rather than an `is` check: this line stops COMPILING if `Prioritizable`
        // is ever declared straight onto `Plugin` without picking a family, which is the mistake
        // worth catching. An `is` check would only warn that it is always true.
        val underAFamily: Content = Prioritizable()
        assertTrue(underAFamily.scope == Scope.Definition, "the worked example must sit under a family")
    }

    // ── The members, and the row they are admitted as ──────────────────────────────────────────
    //
    // Declared after the tests and before nothing: properties initialise in declaration order, so the
    // fixture values come first and `members` — which reads them through `describe` — comes last.

    /**
     * The Core every sweep admits its sample under.
     *
     * No children and no subtree counts: only the Task wire carries them, so a Core holding them
     * would come back changed from the other three kinds and the clamp would refuse on the Core
     * rather than on the plugin under test.
     */
    private val core = Core(
        id = "11111111-0000-4000-8000-000000000001",
        orgSlug = "u-e4h2qk",
        title = "Renew the passport",
        dateCreated = Instant.parse("2026-01-05T08:00:00Z"),
    )

    private val deadline = Instant.parse("2026-03-01T17:00:00Z")
    private val doneAt = Instant.parse("2026-03-01T16:30:00Z")
    private val target = Instant.parse("2026-02-20T00:00:00Z")
    private val github = ExternalRef(ItemSource.GitHub, "Circuit-Stitch/deferno-kmp#1", "https://example.invalid/1")

    /** One dated firing of [core], carrying [sample] — the Occurrence-scoped half of every sweep. */
    private fun firing(sample: Plugin) = Occurrence(core.id, LocalDate(2026, 3, 1), listOf(sample))

    /**
     * Every Family member, one seed instance each, put through [describe].
     *
     * The seed picks the arm; the value comes back from the arm. So a member is enumerated here and
     * stated there, and nowhere is the same fact written a third time.
     */
    private val members: List<Described> = listOf(
        Describable(), Taggable(), Attachable(), Prioritizable(),
        Anchor.Unanchored, Anchor.Deadline(), Anchor.Appointment(), Targeted(),
        Repeats(), Progress(), Trackable(), Outcome(),
        Blocker(), Succeeds(), Importable(), Volition(),
        Dynamics.Unstated, Evaluation(), Purpose(), Obligation(), PersistencePolicy.UntilComplete,
    ).map(::describe)

    /** The sample instances, in [members] order. */
    private val samples: List<Plugin> = members.map { it.sample }
}

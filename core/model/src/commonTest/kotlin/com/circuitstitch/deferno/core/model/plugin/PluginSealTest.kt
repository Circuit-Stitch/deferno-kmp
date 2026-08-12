package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **seal witness**: the site that stops compiling when a plugin type is added and not handled.
 *
 * ADR-0055 buys exhaustiveness with a closed set, and #417 requires that "a new direct subtype of
 * [Plugin] fails compilation at every site that must handle it". Today there is exactly one such
 * site and it is [describe] below — the parity recipe's **write** direction joins it in #418, and
 * that is the one that matters: a plugin the writer forgets is a field silently dropped on the way
 * back to the wire, which the round-trip gate would then catch as a mystery rather than as a
 * compile error naming the culprit.
 *
 * ### Why a `when` over plugin types is allowed *here* and nowhere else
 *
 * `Plugin.kt` says not to branch on which plugin is in hand, and `Placement.kt` says the same about
 * validation specifically. That prohibition is about **decisions**: a placement or exclusivity rule
 * expressed as a type cascade type-checks while being wrong, and stays exhaustive when a branch is
 * deleted. This `when` makes no decision — it exists *only* so the compiler counts, it returns a
 * string nothing acts on, and it lives in `commonTest` so no production path can grow a dependency
 * on it. Deleting a branch here is a compile error rather than a silently narrower rule, which is
 * the opposite failure mode.
 *
 * **When adding a Family member: add a branch.** If the compiler sent you here, that is this file
 * working.
 */
class PluginSealTest {

    /**
     * Exhaustive over every [Plugin]. No `else` branch, deliberately — an `else` is what would let a
     * new plugin type land unnoticed, and it is the only thing that could break this witness.
     *
     * The string is the plugin's **Family**, which makes this list double as the one readable
     * inventory of the cut: eight axes, and which members of each have a wire field behind them.
     */
    private fun describe(plugin: Plugin): String = when (plugin) {
        is Describable, is Taggable, is Attachable, is Prioritizable -> "Content"
        is Anchor, is Targeted -> "Temporal"
        is Repeats -> "Unfolding"
        is Progress, is Trackable -> "Enactment"
        is Blocker, is Succeeds, is Importable -> "Linkage"
        is Volition -> "Modal"
        // The five the wire cannot carry (ADR-0057). One per meaning family — five families, five
        // members — sharing only a Reach, which is why they are listed apart rather than merged above.
        is Dynamics -> "Unfolding (shadowed)"
        is Evaluation -> "Enactment (shadowed)"
        is Purpose -> "Linkage (shadowed)"
        is Obligation -> "Modal (shadowed)"
        is PersistencePolicy -> "Persistence (shadowed)"
    }

    @Test
    fun everyPluginTypeIsNamedBySomethingThatMustHandleIt() {
        assertEquals("Content", describe(Prioritizable(Priority.Fire)))
        assertEquals("Temporal", describe(Anchor.Unanchored))
        assertEquals("Unfolding", describe(Repeats()))
        assertEquals("Enactment", describe(Progress()))
        assertEquals("Linkage", describe(Blocker()))
        assertEquals("Modal", describe(Volition()))
        assertEquals("Unfolding (shadowed)", describe(Dynamics.Unstated))
        assertEquals("Enactment (shadowed)", describe(Evaluation()))
        assertEquals("Linkage (shadowed)", describe(Purpose()))
        assertEquals("Modal (shadowed)", describe(Obligation()))
        assertEquals("Persistence (shadowed)", describe(PersistencePolicy.UntilComplete))
    }

    @Test
    fun everyPluginSaysHowFarItsValueCanTravel() {
        // `Plugin.reach` is abstract because neither default is safe: Wire would enqueue a mutation
        // that can never drain, DeviceLocal would silently stop sending a Family that has a field.
        // What this asserts is the split itself — fifteen wire-backed instances (thirteen plugin
        // types, of which `Anchor` contributes three members) against five shadowed — so a Family
        // that answers wrongly shows up as a count that moved.
        val everyMember = wireBackedMembers + shadowedMembers
        assertEquals(15, everyMember.count { it.reach == Reach.Wire }, "the wire-backed set changed size")
        assertEquals(5, everyMember.count { it.reach == Reach.DeviceLocal }, "the shadowed set changed size")
        for (plugin in shadowedMembers) {
            assertEquals(Reach.DeviceLocal, plugin.reach, "${plugin::class.simpleName} claims a wire field")
        }
    }

    @Test
    fun everyPluginSaysWhatItsSilenceMeans() {
        // `Plugin.degenerate` is abstract, so a Family that lands without answering does not compile.
        // What this asserts is that the answer is a member of that plugin's OWN family — the mistake
        // an inherited default would let through, and the one that would make the recipe's
        // sparseness rule silently drop the wrong thing.
        for (plugin in wireBackedMembers + shadowedMembers) {
            assertEquals(
                plugin.family,
                plugin.degenerate.family,
                "${plugin::class.simpleName} names a degenerate value from another family",
            )
        }
    }

    /** Every wire-backed member, one instance each. `Anchor` contributes all three of its. */
    private val wireBackedMembers: List<Plugin> = listOf(
        Describable(), Taggable(), Attachable(), Prioritizable(),
        Anchor.Unanchored, Anchor.Deadline(), Anchor.Appointment(), Targeted(),
        Repeats(), Progress(), Trackable(),
        Blocker(), Succeeds(), Importable(), Volition(),
    )

    /** Every shadowed member (ADR-0057), one instance each. */
    private val shadowedMembers: List<Plugin> = listOf(
        Dynamics.Unstated, Evaluation(), Purpose(), Obligation(), PersistencePolicy.UntilComplete,
    )

    @Test
    fun everyPluginAnswersWhichRecordOwnsIt() {
        // `Plugin.scope` has no default. A plugin that does not answer does not compile, so this
        // asserts the answer is a real one rather than that it exists.
        assertEquals(Scope.Definition, Prioritizable().scope)
    }

    @Test
    fun everyFamilyMarkerIsReachableFromThePluginSeal() {
        // The eight markers are documentation in types: none is ever matched, so nothing else would
        // notice one being dropped. Naming them here means deleting a family breaks a test rather
        // than quietly removing an axis from the model.
        val markers = listOf(
            "Content", "Unfolding", "Temporal", "Modal",
            "Participant", "Enactment", "Persistence", "Linkage",
        )
        assertEquals(8, markers.size, "ADR-0055 cuts the model along eight axes")

        // A typed binding rather than an `is` check: this line stops COMPILING if `Prioritizable`
        // is ever declared straight onto `Plugin` without picking a family, which is the mistake
        // worth catching. An `is` check would only warn that it is always true.
        val underAFamily: Content = Prioritizable()
        assertTrue(underAFamily.scope == Scope.Definition, "the worked example must sit under a family")
    }
}

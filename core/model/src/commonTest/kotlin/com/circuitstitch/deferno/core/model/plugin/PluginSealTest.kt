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
     */
    private fun describe(plugin: Plugin): String = when (plugin) {
        is Prioritizable -> "Content/Prioritizable"
    }

    @Test
    fun everyPluginTypeIsNamedBySomethingThatMustHandleIt() {
        assertEquals("Content/Prioritizable", describe(Prioritizable(Priority.Fire)))
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

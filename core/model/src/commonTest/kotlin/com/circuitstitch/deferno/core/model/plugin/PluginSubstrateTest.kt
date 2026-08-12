package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The ADR-0055 substrate: [Core] plus a sparse plugin list, the [Scope] seal, and the two runtime
 * checks that buy back what named fields gave for free.
 *
 * What is pinned here is the *shape* of the model, not any Family's meaning — the Families land in
 * #418/#419 and bring their own tests. In particular the placement check is asserted to be a filter
 * over [Scope] and not a cascade over plugin types, because that distinction is the entire reason
 * ADR-0055 makes placement a field.
 */
class PluginSubstrateTest {

    private val core = Core(
        id = "11111111-0000-4000-8000-000000000001",
        orgSlug = "u-e4h2qk",
        title = "Renew the passport",
        dateCreated = Instant.parse("2026-01-05T08:00:00Z"),
    )

    private fun item(vararg plugins: Plugin) = Item(core, plugins.toList())

    // ── Core carries identity and nothing else ─────────────────────────────────────────────────

    @Test
    fun coreCarriesIdentityAndSyncBookkeepingOnly() {
        val full = core.copy(
            parentId = "parent-1",
            childIds = listOf("child-1"),
            sequence = 417,
            ref = "u-e4h2qk-417",
            hydration = HydrationState.Full,
            ownerOrgId = OrgId("org-1"),
        )
        assertEquals("u-e4h2qk-417", full.ref)
        assertEquals(listOf("child-1"), full.childIds)
        assertFalse(full.isDeleted)
    }

    @Test
    fun aTombstoneIsATimestampAndNotADeletedRow() {
        // Same rule as the four kinds today (ADR-0001 LWW): the row stays so a full-snapshot
        // reconcile is idempotent, and `isDeleted` is the read helper over the timestamp.
        val tombstoned = core.copy(deletedAt = Instant.parse("2026-04-01T00:00:00Z"))
        assertTrue(tombstoned.isDeleted)
        assertEquals(core.title, tombstoned.title)
    }

    // ── Every read is total ────────────────────────────────────────────────────────────────────

    @Test
    fun anAbsentPluginReadsAsItsDegenerateValue() {
        // The property the whole accessor convention exists for: no caller handles "absent".
        assertEquals(Prioritizable(), item().priority)
        assertEquals(Priority.Normal, item().priority.priority)
        assertFalse(item().priority.pinned)
    }

    @Test
    fun aLoadedPluginIsWhatTheTotalReadReturns() {
        val pinned = Prioritizable(Priority.Fire, pinned = true)
        assertEquals(pinned, item(pinned).priority)
    }

    @Test
    fun theTotalReadIsTheSameOnBothHosts() {
        // `PluginHost` exists so the reader is written once. An accessor that silently only worked
        // on an Item would be a gap nothing else notices until an Occurrence tries to read it.
        val occurrence = Occurrence("11111111-0000-4000-8000-000000000001", LocalDate(2026, 3, 1))
        assertEquals(Prioritizable(), occurrence.priority)
    }

    @Test
    fun theEscapeHatchDistinguishesAbsentFromDegenerate() {
        // `has` is the one reader that CAN tell "nothing loaded" from "loaded at its degenerate
        // value", and it exists for the Families where those are two different claims — an absent
        // bound is underspecified, not defaulted (#419). Nothing in a UI should reach for it.
        assertFalse(item().has<Prioritizable>())
        assertTrue(item(Prioritizable()).has<Prioritizable>())
    }

    @Test
    fun anAbsentAndAnExplicitlyDegenerateValueReadAlike() {
        // `Priority.Default` already asserts this for the wire: a row the wire omits `priority` on
        // is indistinguishable from one that explicitly said Normal. The plugin list keeps that
        // property rather than introducing a third state.
        assertEquals(item().priority, item(Prioritizable()).priority)
    }

    // ── Placement is a filter over Scope ───────────────────────────────────────────────────────

    @Test
    fun aDefinitionPluginOnAnOccurrenceIsMisplaced() {
        val occurrence = Occurrence(
            itemId = core.id,
            date = LocalDate(2026, 3, 1),
            plugins = listOf(Prioritizable(Priority.Fire)),
        )
        val problems = occurrence.validate()
        assertEquals(1, problems.size, "expected exactly one placement problem, got $problems")
        assertTrue(problems.single().contains("Prioritizable"), problems.single())
        assertTrue(problems.single().contains("an Item"), problems.single())
    }

    @Test
    fun aDefinitionPluginOnAnItemIsWellPlaced() {
        assertEquals(emptyList(), item(Prioritizable(Priority.Fire)).validate())
    }

    @Test
    fun placementIsDecidedByScopeAloneAndNeverByType() {
        // The teeth on "a filter, not a `when`": `misplaced` is handed a list and a scope and never
        // sees which plugin it is holding. Passing the SAME plugin against both scopes must flip the
        // answer purely on the field — which a type cascade, matching on `is Prioritizable`, could
        // not do.
        val plugins = listOf<Plugin>(Prioritizable(Priority.Backlog))
        assertEquals(emptyList(), misplaced(plugins, Scope.Definition))
        assertEquals(1, misplaced(plugins, Scope.Occurrence).size)
    }

    // ── At most one member of a family ─────────────────────────────────────────────────────────

    @Test
    fun twoMembersOfOneFamilyAreExclusivityProblems() {
        // Two answers to one question, not a composition. Every reader of a sparse list takes the
        // first, so a second is silently unread rather than additive.
        val problems = exclusivityProblems(
            listOf(Prioritizable(Priority.Fire), Prioritizable(Priority.Backlog)),
        )
        assertEquals(1, problems.size, "expected one exclusivity problem, got $problems")
        assertTrue(problems.single().contains("Prioritizable"), problems.single())
    }

    @Test
    fun aPluginIsExclusiveWithItselfByDefault() {
        // `Plugin.family` defaults to the plugin's own type, so the grouping is total and no plugin
        // can opt out of the check by simply not overriding anything.
        assertEquals(Prioritizable::class, Prioritizable().family)
    }

    @Test
    fun oneMemberOfEachOfSeveralFamiliesIsFine() {
        assertEquals(emptyList(), exclusivityProblems(listOf(Prioritizable(Priority.Fire))))
    }

    // ── Values that cannot coexist ─────────────────────────────────────────────────────────────

    @Test
    fun aConditionCarryingARecurrenceRuleIsRejectedByValidate() {
        // `unfoldingProblems` is enforced here rather than merely stated: a queue of completable
        // rows for something that is never completed is the permanently-open Task the bound axis
        // exists to stop faking, and `Clamp.admit` routes through this call.
        val problems = item(
            Dynamics.Maintained("inbox below 20"),
            Repeats(Recurrence(Cadence.Daily)),
        ).validate()
        assertEquals(1, problems.size, "expected exactly one coherence problem, got $problems")
        assertTrue(problems.single().contains("Maintained"), problems.single())
    }

    @Test
    fun aConditionWithNoRuleIsFine() {
        // The check reads the RULE, not the plugin. A Chore always loads a `Repeats` whether or not
        // a rule survived the wire, so keying on presence would flag a rule-less one.
        assertEquals(emptyList(), item(Dynamics.Maintained("inbox below 20")).validate())
        assertEquals(emptyList(), item(Dynamics.Maintained("inbox below 20"), Repeats()).validate())
    }

    // ── The conversion primitive ───────────────────────────────────────────────────────────────

    @Test
    fun swappingAFamilyLeavesEveryOtherFamilyUntouched() {
        // ADR-0055's central property, exercised as far as one Family allows: the swap replaces the
        // loaded member of that family and adds nothing. #418 is where this becomes the real
        // conversion test, with several families loaded at once.
        val before = listOf<Plugin>(Prioritizable(Priority.Fire, pinned = true))
        val after = before.replacingFamilyOf(Prioritizable(Priority.Backlog))
        assertEquals(listOf(Prioritizable(Priority.Backlog)), after)
        assertEquals(emptyList(), exclusivityProblems(after))
    }

    @Test
    fun unloadingAFamilyReturnsTheReaderToItsDegenerateValue() {
        val loaded = item(Prioritizable(Priority.Fire, pinned = true))
        val unloaded = loaded.copy(plugins = loaded.plugins.withoutFamilyOf(Prioritizable()))
        assertEquals(Prioritizable(), unloaded.priority)
    }

    // ── The sparse list stays sparse ───────────────────────────────────────────────────────────

    @Test
    fun anItemWithNoPluginsIsValid() {
        // "Sparse" is not a degraded state: a row that says nothing beyond its Core is an ordinary
        // row, and nothing in the substrate requires a plugin to be loaded.
        assertEquals(emptyList(), item().validate())
        assertEquals(emptyList(), item().plugins)
    }
}

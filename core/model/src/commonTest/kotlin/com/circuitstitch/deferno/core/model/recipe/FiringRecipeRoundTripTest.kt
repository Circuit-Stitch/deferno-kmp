package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.plugin.Core
import com.circuitstitch.deferno.core.model.plugin.Evaluation
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import com.circuitstitch.deferno.core.model.plugin.Outcome
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * The ADR-0056 round-trip gate for **one dated firing**: for every kind crossed with every shape its
 * occurrence endpoint can store, reading a fact into plugins and writing it back reproduces it
 * unchanged.
 *
 * `KindRecipeRoundTripTest` is the same gate over definitions, and everything it says about identity
 * beating equivalence, and about guarding the corpus rather than merely using it, applies here. What
 * this file adds is the half of the model that had no wire-backed Family at all until now: an
 * [Occurrence] could carry only a device-local verdict, so nothing it held could be lost on the way to
 * the server, because nothing it held ever went there.
 *
 * Two properties are specific to a firing and have no analogue above:
 *
 *  - **Absence is a value.** A fact *is* its plugin — there is no Core underneath it — so an
 *    [Occurrence] with nothing on record must write back no row rather than an empty one, and a stored
 *    `Scheduled` must stay distinguishable from that absence.
 *  - **The vocabulary is narrower per kind than the type.** `OccurrenceResolution` is the union of
 *    three endpoints' stored sets, and no single kind carries all five, so a fact can round-trip
 *    perfectly and still be unsendable.
 */
class FiringRecipeRoundTripTest {

    @Test
    fun readingAFiringIntoPluginsAndWritingItBackReproducesIt() {
        // THE gate. Failures accumulate and are reported together, the way the definition sweep and
        // `RecurrenceCorpusTest` report theirs.
        val failures = mutableListOf<String>()
        for (shape in FiringShapes.ALL) {
            val roundTripped = shape.write(shape.read())
            if (roundTripped != shape.fact) {
                failures += "── ${shape.label}\n  in:  ${shape.fact}\n  out: $roundTripped"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${FiringShapes.ALL.size} firings did not round-trip:\n\n" +
                    failures.joinToString("\n\n"),
            )
        }
    }

    @Test
    fun everyStoredFiringIsOnePluginAndNoneOfThemIsSilent() {
        // A fact holds exactly one Family's worth of data, so the read is one plugin — and never a
        // plugin equal to its family's silence, which would make the list a fixed-width record.
        for (shape in FiringShapes.ALL) {
            val plugins = shape.read().plugins
            assertEquals(1, plugins.size, "${shape.label} read into ${plugins.size} plugins")
            assertEquals(
                emptyList(),
                plugins.filter { it == it.degenerate },
                "${shape.label} loaded a plugin equal to its degenerate value",
            )
        }
    }

    @Test
    fun nothingOnRecordIsTheAbsenceOfARowRatherThanAnEmptyOne() {
        // The property the definition gate has no analogue for. A Task row at every wire default is
        // still a row; a firing with nothing on record is not one, and writing an empty fact would
        // invent a server record for a date nothing has happened on. `resolveOccurrenceState` reads
        // that absence against today, which is the reading that would be destroyed.
        assertNull(ParityRecipe.writeFact(Occurrence(ITEM_ID, DATE), ItemKind.Chore))
        assertNull(
            ParityRecipe.writeFact(Occurrence(ITEM_ID, DATE, listOf(Evaluation(obtained = true))), ItemKind.Chore),
            "a device-local verdict is not a row the server holds",
        )
        // And the distinction it exists to protect: a stored `Scheduled` is a written row that records
        // no progress, so it survives the trip that absence does not make.
        val scheduled = OccurrenceFact(ItemKind.Event, ITEM_ID, DATE, OccurrenceResolution.Scheduled)
        assertEquals(scheduled, ParityRecipe.writeFact(ParityRecipe.read(scheduled), ItemKind.Event))
    }

    @Test
    fun everyReadFiringIsValidAndSitsOnTheRecordItBelongsTo() {
        val problems = FiringShapes.ALL
            .map { it to it.read().validate() }
            .filter { (_, p) -> p.isNotEmpty() }
            .map { (shape, p) -> "${shape.label}: $p" }
        assertEquals(emptyList(), problems, "the recipe produced invalid firings")

        // The other direction of the same rule: what belongs to a date may not sit on the definition.
        val misplaced = Item(core, listOf(Outcome(OccurrenceResolution.DoneOnTime))).validate()
        assertTrue(
            misplaced.any { it.contains("Outcome") },
            "an Outcome on an Item must be reported as misplaced, got $misplaced",
        )
    }

    @Test
    fun onlyTheKindsWithFiringsContributeShapes() {
        // Over `ItemKind.entries` rather than a hand-listed three, so a fifth kind cannot be added
        // without this failing — the runtime half of the exhaustive `when` in `FiringShapes.shapesOf`.
        for (kind in listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event)) {
            assertTrue(
                FiringShapes.ALL.any { it.kind == kind },
                "$kind contributes no firings; the gate would pass vacuously for it",
            )
        }
        assertEquals(
            emptyList(),
            FiringShapes.ALL.filter { it.kind == ItemKind.Task },
            "a Task has no firings on the wire; shapes for one would be gating a route that does not exist",
        )
    }

    @Test
    fun theCorpusDoesNotSilentlyShrink() {
        // Literal counts, the guard the definition corpus and `RecurrenceCorpusTest` both use: deleting
        // an axis makes the gate cover less while still passing. Update deliberately when an axis is
        // added, never to make a red test green.
        val counted = ItemKind.entries.associateWith { kind -> FiringShapes.ALL.count { it.kind == kind } }
        assertEquals(
            mapOf(
                ItemKind.Task to 0,
                ItemKind.Habit to 12,
                ItemKind.Chore to 16,
                ItemKind.Event to 16,
            ),
            counted,
            "the firing corpus changed size; if that was deliberate, update these counts",
        )
        // Every shape is a distinct fact, so an axis that stopped varying anything shows up as a gap
        // between the two numbers rather than as coverage that is not there.
        assertEquals(
            FiringShapes.ALL.size,
            FiringShapes.ALL.map { it.fact }.toSet().size,
            "two shapes produced the same fact; an axis may have stopped varying anything",
        )
    }

    @Test
    fun everyShapeIsNameable() {
        val labels = FiringShapes.ALL.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "firing labels are not unique")
        assertTrue(labels.all { it.isNotBlank() }, "a firing shape has a blank label")
    }

    @Test
    fun everyDeclaredAxisValueReachesAShape() {
        // The teeth, and the thing that stops the corpus being circular: it is *generated* from
        // `Clamp.storedResolutions`, and these expectations are literal. Narrowing a kind's stored set
        // silently shrinks the sweep, and this is what notices.
        val declared = mapOf(
            ItemKind.Habit to setOf(
                "stored", "Scheduled", "DoneOnTime", "DoneLate",
                "unticked", "ticked", "no-deadline", "deadline",
            ),
            ItemKind.Chore to setOf(
                "stored", "InProgress", "DoneOnTime", "DoneLate", "Skipped",
                "unticked", "ticked", "no-deadline", "deadline",
            ),
            ItemKind.Event to setOf(
                "stored", "Scheduled", "InProgress", "DoneOnTime", "Skipped",
                "unticked", "ticked", "no-deadline", "deadline",
            ),
        )

        for ((kind, expected) in declared) {
            val present = FiringShapes.ALL.filter { it.kind == kind }.flatMap { axisNames(it.label) }.toSet()
            assertEquals(emptySet(), expected - present, "$kind declares axis values that reach no shape")
            assertEquals(emptySet(), present - expected, "$kind produced axis values this test does not know about")
        }
    }

    // ── The representable set ──────────────────────────────────────────────────────────────────

    @Test
    fun noKindStoresTheWholeUnionAndEveryMemberIsStoredBySomeKind() {
        // `OccurrenceResolution` is deliberately the union of three per-kind vocabularies. Both halves
        // of that claim are asserted: nobody carries all five, and nothing in the type is a member no
        // endpoint can hold.
        for (kind in ItemKind.entries) {
            assertTrue(
                Clamp.storedResolutions(kind).size < OccurrenceResolution.entries.size,
                "$kind is claimed to store the whole union, which would make the enum kind-neutral",
            )
        }
        assertEquals(
            OccurrenceResolution.entries.toSet(),
            ItemKind.entries.flatMap { Clamp.storedResolutions(it) }.toSet(),
            "a member of the union is stored by no endpoint",
        )
    }

    @Test
    fun eachKindsStoredVocabularyIsWhatItsEndpointHolds() {
        // Literal, and each exclusion carries its citation in `Clamp.storedResolutions`' KDoc. These
        // are the three sets the corpus is generated from, so this is where they are actually pinned.
        assertEquals(
            setOf(
                OccurrenceResolution.Scheduled,
                OccurrenceResolution.DoneOnTime,
                OccurrenceResolution.DoneLate,
            ),
            Clamp.storedResolutions(ItemKind.Habit),
            "a habit row is {habit_id, date, done_at} — no status column, so no skip and no in-progress",
        )
        assertEquals(
            setOf(
                OccurrenceResolution.InProgress,
                OccurrenceResolution.DoneOnTime,
                OccurrenceResolution.DoneLate,
                OccurrenceResolution.Skipped,
            ),
            Clamp.storedResolutions(ItemKind.Chore),
            "a chore's scheduled and missed are derived rows, so absence is the record",
        )
        assertEquals(
            setOf(
                OccurrenceResolution.Scheduled,
                OccurrenceResolution.InProgress,
                OccurrenceResolution.DoneOnTime,
                OccurrenceResolution.Skipped,
            ),
            Clamp.storedResolutions(ItemKind.Event),
            "events have no late concept; the handler 400s DoneLate at its boundary",
        )
        assertEquals(emptySet(), Clamp.storedResolutions(ItemKind.Task), "a Task has no occurrence route")
    }

    @Test
    fun aResolutionTheEndpointCannotStoreIsRefusedRatherThanQueued() {
        // The point of the clamp: each of these round-trips through the recipe perfectly — it is a
        // straight field copy — and each would still be a mutation that can never drain.
        for ((kind, resolution) in listOf(
            ItemKind.Habit to OccurrenceResolution.Skipped,
            ItemKind.Habit to OccurrenceResolution.InProgress,
            ItemKind.Chore to OccurrenceResolution.Scheduled,
            ItemKind.Event to OccurrenceResolution.DoneLate,
        )) {
            val firing = Occurrence(ITEM_ID, DATE, listOf(Outcome(resolution)))
            assertEquals(
                resolution,
                ParityRecipe.writeFact(firing, kind)?.resolution,
                "the recipe copies it faithfully, which is exactly why a separate check is needed",
            )
            assertIs<FiringAdmission.Refused>(
                Clamp.admit(firing, kind),
                "$kind was allowed to record $resolution",
            )
        }
    }

    @Test
    fun everyShapeInTheCorpusIsAdmittedAsItsOwnKind() {
        // The converse, and the reason the two live together: the corpus is exactly the admissible set,
        // so a clamp that refused one of these would be refusing a row the server itself sent.
        for (shape in FiringShapes.ALL) {
            val admitted = assertIs<FiringAdmission.Admitted>(
                Clamp.admit(shape.read(), shape.kind),
                "${shape.label} was refused",
            )
            assertEquals(shape.fact, admitted.fact, "${shape.label} was admitted as a different row")
            assertTrue(admitted.notSynced.isEmpty(), "${shape.label} claimed something device-local")
        }
    }

    @Test
    fun aVerdictOnADateIsAdmittedAndReportedRatherThanSentOrDropped() {
        // The shadowed half of a firing, on the same terms the item clamp gives a shadowed definition
        // value: not silently destroyed, and not enqueued either.
        val firing = Occurrence(
            ITEM_ID,
            DATE,
            listOf(Outcome(OccurrenceResolution.DoneOnTime, doneAt = DONE_AT), Evaluation(obtained = false)),
        )
        val admitted = assertIs<FiringAdmission.Admitted>(Clamp.admit(firing, ItemKind.Chore))

        assertEquals(OccurrenceResolution.DoneOnTime, admitted.fact?.resolution, "the wire-backed half must send")
        assertEquals(listOf(Evaluation(obtained = false)), admitted.notSynced)
        assertTrue(admitted.hasUnsynced)
    }

    @Test
    fun aRefreshKeepsWhatOnlyThisDeviceHoldsAboutTheDate() {
        // Every reconcile re-pulls the window and last-write-wins over it, so a firing's device-local
        // half is offered up for clobbering on every sync rather than only on an item fetch.
        val held = Occurrence(
            ITEM_ID,
            DATE,
            listOf(Outcome(OccurrenceResolution.InProgress), Evaluation(obtained = true)),
        )
        val fromServer = ParityRecipe.read(
            OccurrenceFact(ItemKind.Chore, ITEM_ID, DATE, OccurrenceResolution.DoneOnTime, doneAt = DONE_AT),
        )

        val refreshed = held.refreshedFrom(fromServer)
        assertEquals(OccurrenceResolution.DoneOnTime, refreshed.outcome.resolution, "the server owns what it stores")
        assertEquals(Evaluation(obtained = true), refreshed.evaluation, "the refresh cleared a device-local verdict")
    }

    // ── The disagreement that is carried rather than corrected ─────────────────────────────────

    @Test
    fun aStoredPunctualityTheTimestampsDoNotSupportIsReadRatherThanRepaired() {
        // The firing's version of the `all_day` disagreement (#420 Q3b): representable, present in real
        // rows, and left alone by the parity recipe. A reading names it; nothing rewrites it.
        val late = Outcome(OccurrenceResolution.DoneLate, doneAt = DONE_AT, carriedDeadline = DEADLINE)
        assertTrue(late.punctualityDisagrees, "finished before the deadline but stored late")

        val onTime = late.copy(resolution = OccurrenceResolution.DoneOnTime)
        assertFalse(onTime.punctualityDisagrees)

        // An unticked row cannot disagree — there is no timestamp to disagree with — and neither can a
        // resolution that makes no claim about punctuality.
        assertFalse(Outcome(OccurrenceResolution.Scheduled).punctualityDisagrees)
        assertFalse(Outcome(OccurrenceResolution.Skipped, doneAt = DONE_AT).punctualityDisagrees)

        // And a firing with nothing on record says nothing at all, including this.
        assertFalse(Outcome().isOnRecord)
        assertFalse(Outcome().punctualityDisagrees)
        assertTrue(Outcome(OccurrenceResolution.Scheduled).isOnRecord, "a written row is on record")
    }

    @Test
    fun aTimestampWithNothingOnRecordIsReportedByTheFiringItself() {
        // The one thing a single `Outcome` can be wrong about on its own: a record of something that
        // was never recorded. Nothing in the corpus produces it — every fact has a resolution — so it
        // is a shape only a caller can assemble, which is who `validate` is for.
        assertTrue(Outcome(doneAt = DONE_AT).validate().isNotEmpty())
        assertTrue(Outcome(carriedDeadline = DEADLINE).validate().isNotEmpty())
        assertEquals(emptyList(), Outcome().validate())
        assertEquals(emptyList(), Outcome(OccurrenceResolution.Scheduled, carriedDeadline = DEADLINE).validate())
    }

    // ── Reading a label ────────────────────────────────────────────────────────────────────────

    /** `Habit/stored/enactment:DoneLate+ticked+deadline` → `[stored, DoneLate, ticked, deadline]`. */
    private fun axisNames(label: String): List<String> {
        val afterKind = label.substringAfter('/')
        val baseline = afterKind.substringBefore('/')
        val picks = afterKind.substringAfter('/').substringAfter(':')
        return listOf(baseline) + picks.split('+')
    }

    private val core = Core(
        id = ITEM_ID,
        orgSlug = "u-e4h2qk",
        title = "Take the bins out",
        dateCreated = Instant.parse("2026-01-05T08:00:00Z"),
    )

    private companion object {
        const val ITEM_ID = "22222222-0000-4000-8000-000000000001"
        val DATE = LocalDate(2026, 3, 1)
        val DEADLINE = Instant.parse("2026-03-01T17:00:00Z")
        val DONE_AT = Instant.parse("2026-03-01T16:30:00Z")
    }
}

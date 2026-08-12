package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The **kind × firing-shape corpus** — [KindShapes]'s sibling for one dated engagement, and what
 * `FiringRecipeRoundTripTest` sweeps.
 *
 * ### It is generated from the representable set, not from a table
 *
 * The wire's occurrence vocabulary is *narrower per kind than the union it condenses to*: a Habit
 * stores no status at all, a Chore has no stored `Scheduled`, an Event has no late arm. Hand-listing
 * that per kind here would put the same fact in two places and let them drift, so the resolution axis
 * is built from [Clamp.storedResolutions] — the one statement of it, carrying the citations. What
 * keeps that honest rather than circular is `FiringRecipeRoundTripTest`, which asserts the axis values
 * that actually reached a shape against **literal** expectations: narrowing a kind's stored set is
 * then a red test rather than a quietly smaller corpus.
 *
 * ### Why the timestamps are producted against every resolution
 *
 * The same reason `KindShapes` products `finishedAt` against every `WorkingState`: the wire can carry
 * a `done_at` on a row that records no completion, and the round trip has to *survive* that rather
 * than tidy it away. A stored punctuality the timestamps do not support is likewise representable —
 * `Outcome.punctualityDisagrees` is the reading for it, and it is deliberately not a repair.
 *
 * ### A kind with no firings is an arm, not an omission
 *
 * [shapesOf] is exhaustive over [ItemKind], so a Task contributing nothing is a decision recorded in a
 * branch. It stops being the right answer the moment a Task grows occurrences, which ADR-0055 expects.
 */
internal object FiringShapes {

    /** Every shape in the corpus, in a stable order. */
    val ALL: List<FiringShape> by lazy { ItemKind.entries.flatMap(::shapesOf) }

    /** Every firing shape of one kind. **Exhaustive over [ItemKind]** — see the class KDoc. */
    fun shapesOf(kind: ItemKind): List<FiringShape> = when (kind) {
        // No Task firing exists on the wire: there is no `tasks/{id}/occurrences` route, and
        // `ItemKind.recurringPath()` errors outright for a Task.
        ItemKind.Task -> emptyList()
        ItemKind.Habit, ItemKind.Chore, ItemKind.Event -> firingsOf(kind)
    }
}

/**
 * One shape in the corpus: a [label] naming how it was built, the [kind] whose endpoint stores it, and
 * the [fact] itself.
 *
 * [kind] is a field rather than read off [fact] because it is what the *write* direction has to be
 * handed — an [Occurrence] names no kind, exactly as an `Item` does not.
 */
internal data class FiringShape(val label: String, val kind: ItemKind, val fact: OccurrenceFact) {

    /** This shape read into plugins. */
    fun read(recipe: KindRecipe = ParityRecipe): Occurrence = recipe.read(fact)

    /** [occurrence] written back as a firing of this shape's kind, or `null` if nothing is on record. */
    fun write(occurrence: Occurrence, recipe: KindRecipe = ParityRecipe): OccurrenceFact? =
        recipe.writeFact(occurrence, kind)
}

// ── Fixture values ─────────────────────────────────────────────────────────────────────────────
//
// Fixed literals, never a clock read — the rule `KindShapes` states and the recurrence corpus before
// it: a corpus whose contents depend on when it runs cannot pin a round trip.

private val date = LocalDate(2026, 3, 1)
private val deadline = Instant.parse("2026-03-01T17:00:00Z")
private val doneAt = Instant.parse("2026-03-01T16:30:00Z")

/** The chain **Head** every firing projects from — never a series or segment id. */
private const val DEFINITION_ID = "22222222-0000-4000-8000-000000000001"

private fun firingsOf(kind: ItemKind): List<FiringShape> {
    val seed = OccurrenceFact(
        kind = kind,
        definitionId = DEFINITION_ID,
        date = date,
        // Overwritten by every shape the resolution axis produces; a seed value is required because a
        // stored fact always has one, which is the asymmetry `Outcome.resolution` being nullable
        // exists to express.
        resolution = OccurrenceResolution.Scheduled,
    )
    return shapes(seed) {
        baseline("stored") { it }
        group("enactment") {
            axis {
                // Enum-declaration order rather than the set's, so the corpus order is stable however
                // the sets in `Clamp` are written.
                OccurrenceResolution.entries
                    .filter { it in Clamp.storedResolutions(kind) }
                    .forEach { resolution -> choice(resolution.name) { it.copy(resolution = resolution) } }
            }
            axis {
                choice("unticked") { it.copy(doneAt = null) }
                choice("ticked") { it.copy(doneAt = doneAt) }
            }
            axis {
                // A firing resolved offline and never synced carries no deadline at all: the optimistic
                // writer copies the one the last synced fact had, and a firing that has none cannot be
                // late, since there is nothing to be late against.
                choice("no-deadline") { it.copy(completeBy = null) }
                choice("deadline") { it.copy(completeBy = deadline) }
            }
        }
    }.map { (label, fact) -> FiringShape("$kind/$label", kind, fact) }
}

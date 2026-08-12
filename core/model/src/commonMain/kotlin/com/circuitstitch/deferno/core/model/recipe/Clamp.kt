@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Occurrence
import com.circuitstitch.deferno.core.model.plugin.Plugin
import com.circuitstitch.deferno.core.model.plugin.Reach
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The clamp: what stops the model holding a value the wire cannot carry (ADR-0057).
 *
 * The plugin model is strictly more expressive than the four kinds, and the outbox replays pending
 * mutations against whatever the server accepts, so a shape the client can hold but cannot send never
 * syncs. Three things the clamp is not:
 *
 * - **Not a drop.** A set carrying a shadowed value is admitted, and the shadowed part comes back in
 *   [Admission.Admitted.notSynced] for a surface to mark. Silently destroying what a person recorded
 *   is refused.
 * - **Not a send.** Nothing in [Admission.Admitted.notSynced] reaches the outbox. The row sent to the
 *   server is built from the wire-backed half alone.
 * - **Not a type check.** Which half a plugin belongs to is [Plugin.reach], a field, so the split is a
 *   `partition` and a Family that forgot to answer does not compile.
 *
 * The test is a round trip: write the wire-backed half to a kind row, read it straight back. A plugin
 * that does not come back has no wire field behind it, so the clamp refuses rather than let it become a
 * mutation that can never drain. The recipes never produce such a set, which the round-trip gate proves,
 * so the clamp is for sets a *caller* assembles.
 *
 * [admit] is the checked entry point, and it hands back the row it proved — once for an [Item]
 * definition, once for one dated [Occurrence] of it. The [KindRecipe] write methods are the unchecked
 * primitive underneath: a caller that reaches for one directly gets a row with no loss detection at
 * all.
 */
@ObjCName("PluginClamp")
object Clamp {

    /**
     * Admit [item] as a row of [kind], or refuse it.
     *
     * An [Item] names no kind, so the caller says which of the four rows it is holding — the same way
     * it does for the write direction.
     */
    fun admit(item: Item, kind: ItemKind, recipe: KindRecipe = ParityRecipe): Admission {
        val problems = item.validate()
        if (problems.isNotEmpty()) return Admission.Refused(problems)

        val (wireBacked, deviceLocal) = item.plugins.partition { it.reach == Reach.Wire }
        val wireOnly = item.copy(plugins = wireBacked)

        val row = recipe.write(wireOnly, kind)
        val roundTripped = recipe.read(row)

        // Only the non-degenerate half counts as a claim. A plugin equal to its family's silence
        // claims nothing, so losing it loses nothing.
        val claimed = wireBacked.filter { it.saysSomething }
        val lost = claimed.filterNot { it in roundTripped.plugins }
        if (lost.isNotEmpty()) {
            return Admission.Refused(
                listOf("$kind has no wire field for: " + lost.joinToString { it::class.simpleName ?: "?" }),
            )
        }

        // The converse is deliberately not checked. A plugin that comes back unmentioned is the kind
        // supplying its own wire default — a Task's `Open`, a Chore's `Rolling` — which is
        // normalisation, not invention.
        if (roundTripped.core != item.core) {
            return Admission.Refused(listOf("$kind cannot carry this Core unchanged"))
        }

        return Admission.Admitted(row, wireOnly, deviceLocal)
    }

    /**
     * Admit one dated firing as a firing of [kind], or refuse it — the [Occurrence] half of [admit],
     * and the same algorithm: split on [Reach], write the wire-backed half, read it straight back, and
     * refuse anything the caller claimed that did not survive.
     *
     * It answers one question the item half does not have to. The wire's occurrence vocabulary is
     * **narrower per kind than the union it condenses to**, so a resolution can round-trip through the
     * recipe perfectly and still be a claim its own endpoint cannot store — see [storedResolutions].
     * That check runs before the round trip, since it is the one that catches a mutation which would
     * never drain.
     *
     * A separate hierarchy from [Admission] rather than one generic in the row type: a generic sealed
     * interface erases on the way to Obj-C, and every one of these types is read from Swift.
     */
    fun admit(
        occurrence: Occurrence,
        kind: ItemKind,
        recipe: KindRecipe = ParityRecipe,
    ): FiringAdmission {
        val problems = occurrence.validate()
        if (problems.isNotEmpty()) return FiringAdmission.Refused(problems)

        val (wireBacked, deviceLocal) = occurrence.plugins.partition { it.reach == Reach.Wire }
        val wireOnly = occurrence.copy(plugins = wireBacked)

        val resolution = wireOnly.outcome.resolution
        if (resolution != null && resolution !in storedResolutions(kind)) {
            return FiringAdmission.Refused(listOf("a $kind firing cannot be recorded as $resolution"))
        }

        val fact = recipe.writeFact(wireOnly, kind)
        val roundTripped = fact?.let(recipe::read)?.plugins.orEmpty()

        val claimed = wireBacked.filter { it.saysSomething }
        val lost = claimed.filterNot { it in roundTripped }
        if (lost.isNotEmpty()) {
            return FiringAdmission.Refused(
                listOf("a $kind firing has no wire field for: " + lost.joinToString { it::class.simpleName ?: "?" }),
            )
        }

        return FiringAdmission.Admitted(fact, deviceLocal)
    }

    /**
     * Which resolutions [kind]'s occurrence endpoints can actually **store** — the firing half of the
     * representable set, and narrower than [OccurrenceResolution] on every kind.
     *
     * That enum is deliberately the *union* of three per-kind vocabularies; this is the split back
     * apart, verified against the Rust (ADR-0053 decision 5 — it is normative):
     *
     * - **Habit.** `HabitOccurrence` is `{habit_id, date, done_at}` and has no status column at all, so
     *   done-ness is a timestamp and the resolution is synthesised at the wire boundary: ticked splits
     *   on punctuality, unticked is a stored `Scheduled`. There is no habit skip and no habit
     *   in-progress — `OutboxOccurrenceWriter.mark` refuses every non-`Complete` habit action, because
     *   the only payload it could build is `{done:false}`, which *un-completes* rather than skips. Nor
     *   is the projection hiding a richer store: the backend records that a dropped habit date "reads
     *   as Scheduled not Dropped until habit storage gains a durable Dropped marker", so the narrowing
     *   is the wire's, not this client's reading of it.
     * - **Chore.** `ChoreOccurrenceStatus` is the four written outcomes. `scheduled` and `missed` are
     *   *derived* rows over the requested window, condensed to `null` by the mapper: for a Chore,
     *   absence is the record, so a stored `Scheduled` does not exist.
     * - **Event.** `OccurrenceStatus` adds the genuinely stored `scheduled`, and drops the late arm:
     *   `validate_for_event` returns a 400 for `DoneLate` at the handler's boundary and the Done arm
     *   hard-codes on-time. [com.circuitstitch.deferno.core.model.plugin.latenessProblems] is the same
     *   rule stated over the anchor instead of the kind, which is the form that outlives the cutover.
     * - **Task.** No firings on the wire. An arm rather than an omission, so a Task growing occurrences
     *   — which ADR-0055 expects, since an `Occurrence` is keyed on `itemId + date` with no kind — is a
     *   decision taken here rather than a default inherited.
     */
    fun storedResolutions(kind: ItemKind): Set<OccurrenceResolution> = when (kind) {
        ItemKind.Habit -> setOf(
            OccurrenceResolution.Scheduled,
            OccurrenceResolution.DoneOnTime,
            OccurrenceResolution.DoneLate,
        )
        ItemKind.Chore -> setOf(
            OccurrenceResolution.InProgress,
            OccurrenceResolution.DoneOnTime,
            OccurrenceResolution.DoneLate,
            OccurrenceResolution.Skipped,
        )
        ItemKind.Event -> setOf(
            OccurrenceResolution.Scheduled,
            OccurrenceResolution.InProgress,
            OccurrenceResolution.DoneOnTime,
            OccurrenceResolution.Skipped,
        )
        ItemKind.Task -> emptySet()
    }
}

/** Whether one dated firing may be held as a firing of some kind, and what of it cannot be sent. */
@ObjCName("PluginFiringAdmission")
sealed interface FiringAdmission {

    /**
     * The firing is representable.
     *
     * [fact] is the row to enqueue, and it is **nullable for the same reason
     * [KindRecipe.writeFact] is**: a firing carrying nothing on record is the absence of a row, not an
     * empty one. [notSynced] stays on this device and a surface must mark it rather than present it as
     * saved — today that is only a verdict on a criterion, which has no wire field anywhere.
     */
    data class Admitted(val fact: OccurrenceFact?, val notSynced: List<Plugin>) : FiringAdmission {
        /** Whether anything here is device-local, and therefore expected to be lost at the cutover. */
        val hasUnsynced: Boolean get() = notSynced.isNotEmpty()
    }

    /** The firing cannot be held as this kind. [reasons] names each value with nowhere to go. */
    data class Refused(val reasons: List<String>) : FiringAdmission
}

/** Whether a plugin set may be held as a row of some kind, and what of it cannot be sent. */
@ObjCName("PluginAdmission")
sealed interface Admission {

    /**
     * The set is representable.
     *
     * [row] is the wire row to enqueue, proven to lose nothing the caller claimed. [synced] is the
     * wire-backed half that goes to the server. [notSynced] stays on this device, and a surface must
     * mark each one as unsynced rather than present it as saved.
     */
    data class Admitted(val row: KindRow, val synced: Item, val notSynced: List<Plugin>) : Admission {
        /** Whether anything here is device-local, and therefore expected to be lost at the cutover. */
        val hasUnsynced: Boolean get() = notSynced.isNotEmpty()
    }

    /** The set cannot be held as this kind. [reasons] names each value with nowhere to go. */
    data class Refused(val reasons: List<String>) : Admission
}

/**
 * A refresh from the server, applied without touching what only this device holds.
 *
 * The server is authoritative for everything it has a field for, and for nothing else. Replacing the
 * cached item with the refreshed one outright would clear every shadowed value on the next sync.
 *
 * The receiver is the row this device holds; [fromServer] is what came back.
 */
fun Item.refreshedFrom(fromServer: Item): Item = fromServer.copy(
    plugins = fromServer.plugins.filter { it.reach == Reach.Wire } +
        plugins.filter { it.reach == Reach.DeviceLocal },
)

/**
 * The same rule for one dated firing, and the place it matters most: every reconcile re-pulls the
 * whole window and last-write-wins over it, so a firing's device-local half is offered up for
 * clobbering far more often than a definition's.
 */
fun Occurrence.refreshedFrom(fromServer: Occurrence): Occurrence = fromServer.copy(
    plugins = fromServer.plugins.filter { it.reach == Reach.Wire } +
        plugins.filter { it.reach == Reach.DeviceLocal },
)

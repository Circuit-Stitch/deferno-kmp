@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Item
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
 * [admit] is the checked entry point, and it hands back the row it proved. The four [KindRecipe]
 * `writeX` methods are the unchecked primitive underneath: a caller that reaches for one directly gets
 * a row with no loss detection at all.
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

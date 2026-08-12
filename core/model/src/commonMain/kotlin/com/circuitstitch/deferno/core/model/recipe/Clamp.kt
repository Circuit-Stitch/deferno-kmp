package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Plugin
import com.circuitstitch.deferno.core.model.plugin.Reach

/**
 * The **clamp**: what stops the model writing cheques the wire cannot cash (ADR-0056/0057).
 *
 * The plugin model is strictly more expressive than the four kinds. Offline-first makes that binding
 * rather than incidental — the outbox holds pending mutations that replay against whatever the
 * server accepts, so a shape the client can hold but cannot send is a row that never syncs.
 *
 * ### Three things it is not
 *
 * - **Not a drop.** A set carrying a shadowed value is admitted, and the shadowed part comes back in
 *   [Admission.Admitted.notSynced] so a surface can mark it. Silently destroying what a person
 *   recorded is rejected outright.
 * - **Not a send.** Nothing in [Admission.Admitted.notSynced] reaches the outbox. The row that goes
 *   to the server is built from the wire-backed half alone.
 * - **Not a type check.** Which half a plugin belongs to is [Plugin.reach], a field, so the split is
 *   a `partition` and a Family that forgot to answer does not compile.
 *
 * ### What "round-trips" means here
 *
 * The wire-backed half is written to a kind row and read straight back. If the plugins that come
 * back are not the plugins that went out, some value the model can hold has no wire field behind it
 * — and the clamp refuses rather than letting it become a mutation that can never drain. The
 * round-trip gate proves this never fires for anything the recipes produce; the clamp is what keeps
 * it true for sets a *caller* assembles.
 */
object Clamp {

    /**
     * Admit [item] as a row of [kind], or refuse it.
     *
     * The kind is a parameter because an [Item] names none — that is the point of it — so the caller
     * says which of the four rows it is holding, exactly as it does for the write direction.
     */
    fun admit(item: Item, kind: ItemKind, recipe: KindRecipe = ParityRecipe): Admission {
        val problems = item.validate()
        if (problems.isNotEmpty()) return Admission.Refused(problems)

        val (wireBacked, deviceLocal) = item.plugins.partition { it.reach == Reach.Wire }
        val wireOnly = item.copy(plugins = wireBacked)

        val roundTripped = when (kind) {
            ItemKind.Task -> recipe.read(recipe.writeTask(wireOnly))
            ItemKind.Habit -> recipe.read(recipe.writeHabit(wireOnly))
            ItemKind.Chore -> recipe.read(recipe.writeChore(wireOnly))
            ItemKind.Event -> recipe.read(recipe.writeEvent(wireOnly))
        }

        val lost = wireBacked.filterNot { it in roundTripped.plugins }
        val gained = roundTripped.plugins.filterNot { it in wireBacked }
        if (lost.isNotEmpty() || gained.isNotEmpty()) {
            return Admission.Refused(
                buildList {
                    if (lost.isNotEmpty()) {
                        add("$kind has no wire field for: " + lost.joinToString { it::class.simpleName ?: "?" })
                    }
                    if (gained.isNotEmpty()) {
                        add("$kind would invent: " + gained.joinToString { it::class.simpleName ?: "?" })
                    }
                },
            )
        }
        if (roundTripped.core != item.core) {
            return Admission.Refused(listOf("$kind cannot carry this Core unchanged"))
        }

        return Admission.Admitted(wireOnly, deviceLocal)
    }
}

/** Whether a plugin set may be held as a row of some kind, and what of it cannot be sent. */
sealed interface Admission {

    /**
     * The set is representable.
     *
     * [synced] is what goes to the server — the wire-backed half, proven to round-trip.
     * [notSynced] is what stays on this device, and every one of them is something a surface a
     * person can act on must mark as unsynced rather than present as saved.
     */
    data class Admitted(val synced: Item, val notSynced: List<Plugin>) : Admission {
        /** Whether anything here is device-local, and therefore expected to be lost at the cutover. */
        val hasUnsynced: Boolean get() = notSynced.isNotEmpty()
    }

    /** The set cannot be held as this kind. [reasons] names each value with nowhere to go. */
    data class Refused(val reasons: List<String>) : Admission
}

/**
 * A refresh from the server, applied without touching what only this device holds.
 *
 * ADR-0057's second promise, as a function: the server is authoritative for everything it has a
 * field for, and authoritative for **nothing else**. Naively replacing the cached item with the
 * refreshed one would clear every shadowed value on the next sync, which is the silent destruction
 * the ADR rejects outright.
 *
 * Receiver is the row this device holds; [fromServer] is what came back.
 */
fun Item.refreshedFrom(fromServer: Item): Item = fromServer.copy(
    plugins = fromServer.plugins.filter { it.reach == Reach.Wire } +
        plugins.filter { it.reach == Reach.DeviceLocal },
)

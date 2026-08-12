@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The **Persistence parity seed** — the one place the client answers *"what becomes of this if the day
 * passes undone?"* as a function rather than as a fact about an item's kind (ADR-0056).
 *
 * Today's client answers with a single bit, carried only in the shape of the kind vocabulary: no
 * `carriesForward` function exists anywhere to diff [carriesForward] against. This is the first place
 * the bit is written down, and each half of it comes from somewhere different.
 *
 * - **Chore carries forward, Habit does not** — from the capture tree, where those two kinds *are* the
 *   two answers. `IfMissed.CarriesForward` on `core:domain`'s `CaptureInput` asks it once at capture
 *   and then discards it; `CaptureInputTest` pins that there is no safe Chore/Habit default.
 * - **Task carries forward, Event does not** — not asked at capture, so this half comes from the
 *   overdue rule: habits and events are never overdue, only tasks and chores. A Task with a past
 *   deadline stays on the plan and counts as overdue; an Event's occurrence for a past day is gone.
 *
 * One bit onto a five-member family leaves three members unreachable, which is what a *parity* seed is
 * for. A lapsing kind seeds [PersistencePolicy.ExpiresAfterWindow] ("gone, nothing recorded") and not
 * [PersistencePolicy.SkippedIfMissed] ("gone, and the miss is logged"): nothing here logs a miss today.
 */
@ObjCName("PluginPersistenceSeed")
object PersistenceSeed {

    /**
     * The policy an item of [kind] carries under parity, derived from [carriesForward] alone. That
     * makes "no behaviour changes" checkable rather than asserted.
     */
    fun of(kind: ItemKind): PersistencePolicy =
        if (carriesForward(kind)) PersistencePolicy.UntilComplete else PersistencePolicy.ExpiresAfterWindow

    /**
     * The one bit today's client answers this question with.
     *
     * Exhaustive over [ItemKind] rather than a `when` with an `else`, so a fifth kind cannot inherit
     * an answer nobody chose for it. The class KDoc says where each half comes from.
     */
    fun carriesForward(kind: ItemKind): Boolean = when (kind) {
        ItemKind.Task -> true
        ItemKind.Chore -> true
        ItemKind.Habit -> false
        ItemKind.Event -> false
    }
}

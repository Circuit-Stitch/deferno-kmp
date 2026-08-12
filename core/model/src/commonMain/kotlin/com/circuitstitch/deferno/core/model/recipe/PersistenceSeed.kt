@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.recipe

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.plugin.PersistencePolicy
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * The **Persistence parity seed** — the one place the client answers *"what becomes of this if the
 * day passes undone?"* as a function rather than as a fact about an item's kind.
 *
 * ### There is no `carriesForward` to reproduce
 *
 * ADR-0056 and #419 both name `carriesForward` as the reading this seed must match. **No such
 * function exists in this client.** The only thing bearing the name is `IfMissed.CarriesForward` in
 * `core:domain`'s `CaptureInput` — an *input* to the ADR-0036 capture tree that derives which kind to
 * create (carries-forward → Chore, lapses → Habit) and is then discarded. It is asked once, at
 * capture, and never read again.
 *
 * So the bit exists only in the shape of the kind vocabulary, and [carriesForward] below is the
 * first time this codebase writes it down. That matters for review: there is nothing to diff this
 * against, so the two halves are justified separately —
 *
 * - **Chore carries forward, Habit does not.** Directly from the capture tree: those two kinds *are*
 *   the two answers to that question, and a missing answer there is an error rather than a
 *   default — `CaptureInputTest` pins that, "there is no safe Chore/Habit default".
 * - **Task carries forward, Event does not.** Not asked at capture — the tree reaches them by other
 *   questions — so this half comes from what the rest of the client does. A Task with a past
 *   deadline stays on the plan and is counted as overdue; an Event's occurrence for a past day is
 *   simply gone, and the documented rule for any future cross-kind overdue arm is that "habits and
 *   events are never overdue, only tasks and chores".
 *
 * ### Only two of five policies are reachable, deliberately
 *
 * The seed maps one bit onto a five-member family, so three members stay unreachable until #420
 * ratifies what each kind should actually claim. That is the whole point of a *parity* seed: **no
 * behaviour changes.** In particular a lapsing kind seeds [PersistencePolicy.ExpiresAfterWindow]
 * ("gone, nothing recorded") and **not** [PersistencePolicy.SkippedIfMissed] ("gone, and the miss is
 * logged") — the reference fixtures reach for the latter, and adopting it would start writing
 * history nobody asked for while claiming to be a re-model.
 */
@ObjCName("PluginPersistenceSeed")
object PersistenceSeed {

    /**
     * The policy an item of [kind] carries under parity — derivable from [carriesForward] alone,
     * which is what makes "no behaviour changes" checkable rather than asserted.
     */
    fun of(kind: ItemKind): PersistencePolicy =
        if (carriesForward(kind)) PersistencePolicy.UntilComplete else PersistencePolicy.ExpiresAfterWindow

    /**
     * The one bit today's client answers this question with, written down for the first time.
     *
     * Exhaustive over [ItemKind] rather than a `when` with an `else`, so a fifth kind cannot inherit
     * an answer nobody chose for it. See the class KDoc for where each half comes from.
     */
    fun carriesForward(kind: ItemKind): Boolean = when (kind) {
        ItemKind.Task -> true
        ItemKind.Chore -> true
        ItemKind.Habit -> false
        ItemKind.Event -> false
    }
}

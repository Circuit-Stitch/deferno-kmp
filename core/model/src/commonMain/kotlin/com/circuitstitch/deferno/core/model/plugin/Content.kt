@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Priority
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

// The Content family — what the thing *is*.
//
// Four members, all Scope.Definition, all four of today's kinds carrying every one of them except
// [Attachable]. This is the family with no traps: nothing here is conflated, nothing is derived, and
// the parity recipe copies each field across untouched.

/**
 * Prose. `null` and `""` are **two different wire values** and both survive, which is why this is
 * `String?` where the reference model's is `String = ""`: the four kinds carry `description: String?`
 * and a summary row's `null` means *not hydrated*, not *empty*.
 */
@ObjCName("PluginDescribable")
data class Describable(
    // `description` collides with `-[NSObject description]` in the Apple export and would land as the
    // compiler-picked (and therefore unstable) `description_`. Named at the declaration, exactly as
    // the four kinds' own `description` is — and to the SAME Swift name, so a Phase-5 View reading a
    // plugin and one reading a kind row spell it identically.
    @property:ObjCName("itemDescription") val description: String? = null,
) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Describable()
}

/**
 * Tags. Separate from [Describable] so the two can move independently — the backend's occurrence
 * record already carries `labels_override` alongside `description_override`.
 */
@ObjCName("PluginTaggable")
data class Taggable(val labels: List<String> = emptyList()) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Taggable()
}

/**
 * The backend-hosted attachment **rollup** (#311) — a count and a summed size, not the files.
 *
 * Deliberately narrower than the reference model's `Attachable`, which holds attachments, comments
 * and a transcript. This client caches only what powers offline "has attachment" search and the
 * attachment-size sort (ADR-0042); the files themselves live behind `core:data`'s attachment
 * repository and the comments behind the item-history cache (ADR-0043). Widening this plugin to
 * hold them would mean the recipe layer reaching outside the row it is translating.
 *
 * **Task-only today** — the recurring kinds carry no attachment metadata on the wire yet — so a
 * Habit, Chore or Event never loads it and always reads the degenerate `(0, 0)`.
 */
@ObjCName("PluginAttachable")
data class Attachable(
    val attachmentCount: Int = 0,
    val attachmentTotalSize: Long = 0,
) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Attachable()

    /** Whether this item has at least one backend-hosted attachment. The read `Task.hasAttachment` gives. */
    val hasAttachment: Boolean get() = attachmentCount > 0
}

/**
 * Explicit, deadline-independent urgency plus the pin flag — the **worked example** the accessor
 * convention in [PluginHost] is written from, and the Family member #417 landed on its own.
 *
 * The epic's own table marks Content/`Priority` as *"already `Fire/Normal/Backlog` — identical, no
 * mapping needed"*, so nothing here is a translation decision: this plugin **wraps** the shipped
 * [Priority] enum rather than restating its vocabulary, the same rule that keeps [Repeats] off the
 * occurrence expander (ADR-0053).
 *
 * ### Content, not [Modal]
 *
 * Priority is a property of the thing's place in a list, not a claim about obligation or desire.
 * Keeping it out of [Modal] is what stops that family drifting into a second junk drawer now that
 * [Volition] sits in it.
 *
 * ### Degenerate value
 *
 * `Prioritizable()` — `Normal`, unpinned — which is exactly what all four kinds carry when the wire
 * omits `priority`/`pinned`. So an item with no Content plugin loaded reads the same as one that
 * explicitly said "normal", which is the property `Priority.Default` already asserts.
 */
@ObjCName("PluginPrioritizable")
data class Prioritizable(
    val priority: Priority = Priority.Default,
    val pinned: Boolean = false,
) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Prioritizable()
}

@file:OptIn(ExperimentalObjCName::class)

package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Priority
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

// The Content family — what the thing *is*. Four Scope.Definition members; all four of today's kinds
// carry every one of them except Attachable.

/**
 * Prose. `String?`, not `String`: `null` and `""` are different wire values and both survive. On a
 * summary row `null` means *not hydrated*, `""` means *empty*.
 */
@ObjCName("PluginDescribable")
data class Describable(
    // `description` collides with `-[NSObject description]` in the Apple export, so the Swift name is
    // pinned here — to the name the four kinds' own `description` already exports to.
    @property:ObjCName("itemDescription") val description: String? = null,
) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Describable()
}

/**
 * Tags. Separate from [Describable] so the two move independently — the occurrence record carries
 * `labels_override` alongside `description_override`.
 */
@ObjCName("PluginTaggable")
data class Taggable(val labels: List<String> = emptyList()) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Taggable()
}

/**
 * The backend-hosted attachment **rollup** — a count and a summed size, powering offline "has
 * attachment" search and the attachment-size sort. Not the files: those live behind `core:data`'s
 * attachment repository. Task-only, because the recurring kinds carry no attachment metadata on the
 * wire, so a Habit, Chore or Event always reads the degenerate `(0, 0)`.
 */
@ObjCName("PluginAttachable")
data class Attachable(
    val attachmentCount: Int = 0,
    val attachmentTotalSize: Long = 0,
) : Content {
    override val scope get() = Scope.Definition
    override val reach get() = Reach.Wire
    override val degenerate get() = Attachable()

    /** Whether this item has at least one backend-hosted attachment. */
    val hasAttachment: Boolean get() = attachmentCount > 0
}

/**
 * Explicit, deadline-independent urgency plus the pin flag. Wraps the shipped [Priority] enum rather
 * than restating it, so nothing is translated in either direction. Content, not [Modal]: priority
 * places the item in a list and claims nothing about obligation or desire. The degenerate value is
 * `Normal`, unpinned — what all four kinds carry when the wire omits `priority`/`pinned`, and what
 * `Priority.Default` already asserts.
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

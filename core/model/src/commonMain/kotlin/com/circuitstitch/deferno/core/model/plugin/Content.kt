package com.circuitstitch.deferno.core.model.plugin

import com.circuitstitch.deferno.core.model.Priority

/**
 * Explicit, deadline-independent urgency plus the pin flag — the **worked example** for the accessor
 * convention in `PluginHost.kt`, and the only Family member this slice lands.
 *
 * It is deliberately the dullest one available. The epic's own table marks Content/`Priority` as
 * *"already `Fire/Normal/Backlog` — identical, no mapping needed"*, so nothing here is a translation
 * decision that #418 would then have to re-litigate: this plugin **wraps** the shipped [Priority]
 * enum rather than restating its vocabulary, which is the same rule that keeps the recurrence plugin
 * off the occurrence expander (ADR-0053).
 *
 * ### Content, not [Modal]
 *
 * Priority is a property of the thing's place in a list, not a claim about obligation or desire.
 * Keeping it out of [Modal] is what stops that family drifting into a second junk drawer once
 * volition and obligation land beside it.
 *
 * ### Degenerate value
 *
 * `Prioritizable()` — `Normal`, unpinned — which is exactly what all four of today's kinds carry
 * when the wire omits `priority`/`pinned`. So an item with no Content plugin loaded reads the same
 * as one that explicitly said "normal", which is the property `Priority.Default` already asserts.
 */
data class Prioritizable(
    val priority: Priority = Priority.Default,
    val pinned: Boolean = false,
) : Content {
    override val scope get() = Scope.Definition
}

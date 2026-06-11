package com.circuitstitch.deferno.desktop.chrome

import com.circuitstitch.deferno.core.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Today's-plan count → the dock-icon badge (#117): the number of tasks still to do on the Active
 * Account's plan for today, cleared when the plan is finished, empty, or no Account is active.
 *
 * The host re-points [trackPlan] at each (re)built Account session's plan flow and clears it on
 * sign-out — the same re-pointing discipline as the root's theme flow, so an account switch never
 * shows the prior Account's count (account isolation, ADR-0002/0014). `collectLatest` cancels the
 * previous plan's subscription on each re-point.
 *
 * Where the desktop can't badge ([ChromeBackend.badgeSupported] `false` — Linux/Windows) no
 * collector ever starts: the plan flow isn't even subscribed, so those hosts are unaffected.
 */
class PlanBadge(
    private val backend: ChromeBackend,
    scope: CoroutineScope,
) {
    /** The Active Account's today-plan flow; `null` = no Active Account (clears the badge). */
    private val plan = MutableStateFlow<Flow<List<Task>>?>(null)

    init {
        if (backend.badgeSupported) {
            scope.launch {
                plan.collectLatest { tasks ->
                    (tasks ?: flowOf(emptyList()))
                        .map(::planBadgeText)
                        .distinctUntilChanged()
                        .collect { backend.setBadge(it) }
                }
            }
        }
    }

    /** Re-point the badge at the Active Account's today-plan, or clear it with `null` (sign-out). */
    fun trackPlan(plan: Flow<List<Task>>?) {
        this.plan.value = plan
    }
}

/**
 * The badge text for today's plan: the count of tasks still to do — not Done/Dropped, not a
 * tombstone — and `null` (no badge) when nothing remains, so finishing the plan clears the dock.
 */
internal fun planBadgeText(tasks: List<Task>): String? =
    tasks.count { !it.workingState.isTerminal && !it.isDeleted }
        .takeIf { it > 0 }
        ?.toString()

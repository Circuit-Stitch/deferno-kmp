package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineScope] bound to this component's Essenty lifecycle: cancelled when the component is
 * destroyed, so its state-observation coroutines never outlive it. [context] is the dispatcher the
 * state is produced on — `Dispatchers.Default` in production (the View observes via Compose on the
 * main thread), overridden with a test dispatcher in `commonTest` for deterministic runs.
 */
internal fun ComponentContext.componentScope(context: CoroutineContext): CoroutineScope {
    val scope = CoroutineScope(context + SupervisorJob())
    lifecycle.doOnDestroy { scope.cancel() }
    return scope
}

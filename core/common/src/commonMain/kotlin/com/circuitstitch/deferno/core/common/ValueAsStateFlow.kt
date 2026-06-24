package com.circuitstitch.deferno.core.common

import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirror a Decompose [Value] into a [StateFlow], applying [transform] to each emission — the seam that
 * lets SKIE bridge Decompose navigation to Swift. SKIE bridges `StateFlow` (and the sealed types it
 * carries → Swift enums) but not Decompose's `Value`/`ChildStack`/`ChildSlot`, so the iOS/macOS apps
 * expose `Value.asStateFlow(scope) { it.active.instance }` instead of a hand-written `Value` bridge.
 *
 * The seed is computed synchronously from the current `value`, so SKIE's `.value` is populated for the
 * first SwiftUI render. The underlying `Value.subscribe` is cancelled when [scope] completes; even if
 * it isn't, the subscription only outlives nothing (the source `Value` and this flow are GC'd with the
 * component that owns them).
 */
fun <S : Any, T> Value<S>.asStateFlow(scope: CoroutineScope, transform: (S) -> T): StateFlow<T> {
    val flow = MutableStateFlow(transform(value))
    val cancellation = subscribe { flow.value = transform(it) }
    scope.coroutineContext[Job]?.invokeOnCompletion { cancellation.cancel() }
    return flow.asStateFlow()
}

/** Identity overload: mirror a [Value] straight through to a [StateFlow] of the same type. */
fun <T : Any> Value<T>.asStateFlow(scope: CoroutineScope): StateFlow<T> = asStateFlow(scope) { it }

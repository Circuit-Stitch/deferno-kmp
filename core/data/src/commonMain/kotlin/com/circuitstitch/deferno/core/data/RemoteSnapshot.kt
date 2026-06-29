package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.network.ApiResult

/**
 * The outcome of an offline-first **snapshot read** (ADR-0001): the server's current [Available]
 * state — possibly an empty list — or [Unavailable] when the pull could not reach the server. It keeps
 * "the server has nothing" distinct from "we couldn't ask", so a reconcile **purges** on a genuinely
 * empty snapshot yet **leaves the cache intact** when offline. An `emptyList()`/`null` sentinel can no
 * longer be mistaken for a failed pull (the bug: a server that legitimately emptied could never reconcile
 * the stale local rows away, because empty read as "offline → skip").
 *
 * The read-side mirror of [com.circuitstitch.deferno.core.data.auth.MeResult] — same `Unavailable` term
 * for "couldn't reach the server", here over the background reads the offline-first repositories reconcile
 * through. (Global search dropped its own `Unavailable` result when it went offline — #311.)
 */
sealed interface RemoteSnapshot<out T> {
    data class Available<out T>(val value: T) : RemoteSnapshot<T>
    data object Unavailable : RemoteSnapshot<Nothing>
}

/**
 * Condense an [ApiResult] to a [RemoteSnapshot] at a remote-source boundary: a success becomes
 * [RemoteSnapshot.Available], and every [ApiResult.Failure] mode (offline transport, 4xx/5xx, an
 * unsupported version) collapses to [RemoteSnapshot.Unavailable]. The single place the offline-first
 * "failure ⇒ leave the cache alone" rule lives, rather than an `emptyList()`/`null` sentinel re-encoded
 * per source. Compose with [com.circuitstitch.deferno.core.network.map] to transform the payload first:
 * `requestApi<Dto>{…}.map { it.toDomain() }.asSnapshot()`.
 */
fun <T> ApiResult<T>.asSnapshot(): RemoteSnapshot<T> = when (this) {
    is ApiResult.Success -> RemoteSnapshot.Available(data)
    is ApiResult.Failure -> RemoteSnapshot.Unavailable
}

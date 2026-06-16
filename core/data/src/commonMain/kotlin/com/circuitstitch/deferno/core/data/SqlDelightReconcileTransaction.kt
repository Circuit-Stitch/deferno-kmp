package com.circuitstitch.deferno.core.data

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a local-store reconcile [block] as one atomic SQLDelight transaction, passing [store] (the
 * caller's own local store) as the block's receiver. Shared by every `*LocalStore.transaction`
 * (Task/Habit/Chore/Event) so the `/items` reconcile (ADR-0034, #226) commits each kind's batch of
 * upserts + deletes together and fires that kind's query listeners exactly once, at commit.
 *
 * SQLDelight's `transaction { }` body is **non-suspending**, so the `suspend` [block] cannot simply be
 * awaited inside it. But every mutation a reconcile issues through a local store is a synchronous
 * SQLDelight query — the `suspend` on the store interface exists only so the in-memory fake can be
 * genuinely async. So the block runs to completion synchronously: it is driven with [startCoroutine]
 * and any failure (or a — never expected — real suspension) is surfaced by inspecting the completion.
 */
internal suspend fun <S> DefernoDatabase.reconcileTransaction(store: S, block: suspend (S) -> Unit) {
    var outcome: Result<Unit>? = null
    transaction {
        block.startCoroutine(store, Continuation(EmptyCoroutineContext) { result -> outcome = result })
    }
    val result = checkNotNull(outcome) {
        "the reconcile block suspended on something other than the synchronous local store; " +
            "a local-store transaction requires a non-suspending body"
    }
    result.getOrThrow()
}

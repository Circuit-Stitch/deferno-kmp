package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * Scriptable [TaskRemoteSource] for the repository tests (#22). A test sets [details] to the full payloads
 * a [hydrate] fetches, then asserts how the repository handles them. [failNext] simulates the offline-first
 * failure path. (The cold list snapshot moved to [com.circuitstitch.deferno.core.data.item.ItemSnapshotSource]
 * — #226; global search went offline — #311 — so neither is scripted here.)
 */
class FakeTaskRemoteSource(
    var details: Map<TaskId, Task> = emptyMap(),
    var failNext: Boolean = false,
) : TaskRemoteSource {

    override suspend fun fetch(id: TaskId): Task? {
        if (failNext) return null
        return details[id]
    }
}

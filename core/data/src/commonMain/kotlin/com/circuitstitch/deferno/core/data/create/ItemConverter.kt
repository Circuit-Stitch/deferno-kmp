package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload

/**
 * `POST /items/{id}/convert` — change an existing item's kind to [ConvertItemPayload.to]. Returns the converted
 * item's new kind ([ConvertedItem]) so the caller can reconcile the local cache (remove the old-kind row,
 * seed the new-kind row).
 *
 * This is the seam [OfflineCreateWriter] holds, and it carries no stamp because minting one is not the
 * writer's job. [LedgerRecordingItemConverter] implements it, mints, forwards to [StampedItemConverter],
 * and records the local row — so convert's audit trail is a seam's responsibility, like every other write
 * path's, rather than one writer's private habit that the next writer has no reason to copy.
 */
interface ItemConverter {
    suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem>
}

/**
 * The **wire half** of convert (#364): the same call with the client-minted Activity-ledger merge key
 * required.
 *
 * Split from [ItemConverter] rather than expressed as an optional parameter so the stamp cannot be omitted
 * by accident. Convert is online-only, so it never reaches the outbox choke-point that stamps every other
 * mutation; a convert that reaches the server unstamped gets a server-minted entry id this device has
 * never heard of, so the `?since=` reconcile supersedes the optimistic row instead of merging it and the
 * same kind change shows up twice in the feed until the local one ages out (ADR-0048). A nullable
 * parameter with a default made that failure reachable from every caller and every fake; requiring the
 * value makes it a compile error.
 *
 * That is also why [KtorItemConverter] implements ONLY this half. If it implemented [ItemConverter] too
 * it would hand any caller an unstamped convert straight onto the wire — the exact failure the split
 * exists to prevent.
 *
 * `internal` for the same reason as
 * [com.circuitstitch.deferno.core.data.task.StampedAttachmentSource]: the stamp exists only between the
 * one class that mints it and the one adapter that puts it on the wire.
 */
internal interface StampedItemConverter {
    suspend fun convert(id: String, payload: ConvertItemPayload, stamp: ActivityStamp): ApiResult<ConvertedItem>
}

/**
 * The outcome of a successful convert: exactly one of the four domain kinds the item became. A sealed
 * result (not a nullable quartet) so the caller's reconcile is an exhaustive `when`.
 */
sealed interface ConvertedItem {
    val kind: ItemKind

    data class AsTask(val task: Task) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Task
    }

    data class AsHabit(val habit: Habit) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Habit
    }

    data class AsChore(val chore: Chore) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Chore
    }

    data class AsEvent(val event: Event) : ConvertedItem {
        override val kind: ItemKind get() = ItemKind.Event
    }
}

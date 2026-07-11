package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.data.outbox.CommentReplayListener
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.repointId

/**
 * The production [CommentReplayListener] (ADR-0043): resolves an offline comment create the instant its
 * outbox entry replays. Because the backend never honours the client comment id, the rekey is the
 * mainline — *every* replay diverges:
 *
 * - **Replayed with a server id.** [onReplayed] rekeys the optimistic `commentEntity` row [clientId] →
 *   [serverId] ([CommentLocalStore.rekey], an in-place `UPDATE`; the schema has no FK), then re-points
 *   any already-queued `comment:<clientId>` edit/delete to the server id via the shared
 *   [com.circuitstitch.deferno.core.data.outbox.repointId] outbox sweep (the same helper the #185
 *   [com.circuitstitch.deferno.core.data.create.ItemIdHealer] uses). It returns `true` **iff** it
 *   re-pointed a queued entry, so the processor breaks its now-stale `pending()` pass (a lone create
 *   with no queued edit leaves the outbox unchanged, so the pass may continue).
 * - **Blank server id ⇒ loud, never silent.** The backend never honours the client comment id (no `id`
 *   on `CreateCommentPayload`, Deferno#559), so a blank id is *never* "the server honoured the client
 *   id" — it means the `201` body's id could not be parsed (a contract drift or a client bug, e.g. the
 *   missing-serializer regression that shipped `parseCreatedId` as a silent no-op). [onReplayed] logs it
 *   at ERROR and refuses to rekey (rekeying *to* a blank id would corrupt the row). The optimistic row is
 *   preserved, but it stays under its throwaway client id — so the on-open refresh pulls the server copy
 *   in as a **duplicate** and any queued edit/delete lands on the dead client id (`404` → lost). Logging
 *   makes that data loss diagnosable instead of vanishing.
 * - **Echoed id (server == client).** The row is already correctly keyed — a silent no-op.
 * - **Rejected.** Not handled here: the processor dead-letters a terminally-rejected create (the
 *   optimistic row is preserved, never undone — the user's comment must not silently vanish).
 */
class DefaultCommentReplayListener(
    private val localStore: CommentLocalStore,
    private val outbox: OutboxStore,
) : CommentReplayListener {

    private val log = Logger("CommentReplay")

    override suspend fun onReplayed(taskId: String, clientId: String, serverId: String): Boolean {
        if (serverId.isBlank()) {
            // Never legitimate for a comment (the backend never echoes/honours the client id): the create
            // response id was unparseable. Do NOT rekey — rekeying to a blank id would corrupt the row.
            // Log loudly so the resulting duplicate + lost edit is diagnosable instead of silent; the
            // optimistic row is left intact under its client id for a later replay to heal.
            log.e {
                "comment-create replay (task=$taskId, client=$clientId) got a BLANK server id — the 201 " +
                    "envelope id was unparseable, so the optimistic comment cannot be re-keyed: it will " +
                    "duplicate on the next refresh and its edits/deletes will 404. Check the create contract."
            }
            return false
        }
        // An echoed id (server == client) means the row is already correctly keyed — nothing to do.
        if (serverId == clientId) return false
        localStore.rekey(clientId, serverId)
        // Re-point any queued `comment:<clientId>` edit/delete so it lands on the real comment; the
        // boolean tells the processor whether the queue moved (a lone create leaves it unchanged).
        return outbox.repointId(clientId, serverId)
    }
}

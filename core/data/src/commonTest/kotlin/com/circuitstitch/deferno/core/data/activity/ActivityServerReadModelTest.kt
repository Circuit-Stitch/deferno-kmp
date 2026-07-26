package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The read-time derivations for rows carrying the **server** vocabulary (#364): [ActivityEntry.summaryInfo],
 * [ActivityEntry.itemId], [ActivityEntry.changes], [ActivityEntry.displayAt] and
 * [ActivityEntry.isAcknowledged].
 *
 * Every `detail` blob below is a real shape the backend emits — copied from `handlers/common.rs`
 * (`json_field_diff` + `ledger_record_item_update`), `handlers/items.rs`, `handlers/occurrences.rs`,
 * `handlers/items_plan.rs` and their siblings — not an invented one. A test built on a made-up envelope
 * would pass forever while the feed rendered blanks against the real server.
 *
 * The sibling [ActivityDiffTest] covers the pre-#364 locally-captured `body`/`before` path; this file
 * covers the server half and, crucially, the precedence between the two.
 */
class ActivityServerReadModelTest {

    // The actor's wall-clock (what the feed sorts by), the server clock, and this device's apply time.
    // Deliberately hours apart: the offline case is exactly why the three axes exist.
    private val occurred = Instant.parse("2026-07-20T09:00:00Z")
    private val observed = Instant.parse("2026-07-20T17:00:03Z")
    private val recorded = Instant.parse("2026-07-20T17:00:00Z")

    /** A row as the `?since=` reconcile leaves it: server half populated, no outbox capture at all. */
    private fun serverEntry(
        actionKind: ActivityActionKind,
        detail: String? = null,
        occurrence: String? = null,
        serverItemId: String? = "srv-1",
        target: String = "",
        method: OutboxMethod = OutboxMethod.Post,
        body: String? = null,
        before: String? = null,
        occurredAt: Instant? = occurred,
        observedAt: Instant? = observed,
    ) = ActivityEntry(
        seq = 1,
        recordedAt = recorded,
        source = ActivitySource.Website,
        target = target,
        method = method,
        path = emptyList(),
        body = body,
        before = before,
        entryId = "e-1",
        occurredAt = occurredAt,
        observedAt = observedAt,
        actionKind = actionKind,
        actorKind = ActivityActorKind.Human,
        serverItemId = serverItemId,
        occurrence = occurrence,
        detail = detail,
    )

    // ── The verb vocabulary ────────────────────────────────────────────────────────────────────────

    @Test
    fun everyServerActionKindRendersItsOwnVerbWithTheRealDetailBlobTheBackendEmits() {
        // One row per known `action_kind`, each carrying the detail its handler actually writes. The
        // regression this catches is a verb silently collapsing into "Updated an item" — the ledger is
        // forensic, so a mis-mapped verb misreports what happened rather than merely looking untidy.
        val cases = listOf(
            // handlers/items.rs create seam → recorder.rs record_created
            ActivityActionKind.Created to
                ("""{"title":"Buy milk","item_kind":"task"}""" to ActivitySummary(ActivityVerb.Created, "task")),
            // common.rs ledger_record_item_update, the non-status branch
            ActivityActionKind.Updated to
                ("""{"item_kind":"task","fields":{"title":{"old":"a","new":"b"}}}""" to ActivitySummary(ActivityVerb.UpdatedTask)),
            // handlers/items.rs delete seam
            ActivityActionKind.Deleted to
                ("""{"item_kind":"task","title":"Buy milk"}""" to ActivitySummary(ActivityVerb.DeletedTask)),
            // common.rs, the "status was the only changed field" branch
            ActivityActionKind.StatusChanged to
                ("""{"item_kind":"task","title":"Buy milk","from":"todo","to":"done"}""" to ActivitySummary(ActivityVerb.StatusChanged)),
            // handlers/items.rs move seam
            ActivityActionKind.Moved to
                (
                    """{"item_kind":"task","title":"Buy milk","from_parent_id":"p-1","to_parent_id":"p-2","position":3}""" to
                        ActivitySummary(ActivityVerb.MovedItem)
                    ),
            // handlers/recurrence_split.rs record_segment_split
            ActivityActionKind.Split to
                (
                    """{"title":"Stretch","item_kind":"habit","predecessor_id":"h-0","split_at":"2026-07-20T00:00:00Z"}""" to
                        ActivitySummary(ActivityVerb.Split)
                    ),
            // handlers/items.rs merge seam
            ActivityActionKind.Merged to
                (
                    """{"item_kind":"task","surviving_title":"Buy milk","merged_source_ids":["t-2","t-3"],"merged_count":2}""" to
                        ActivitySummary(ActivityVerb.Merged)
                    ),
            // handlers/convert.rs — note this one carries no `item_kind` at all, only from_kind/to_kind
            ActivityActionKind.Converted to
                ("""{"from_kind":"task","to_kind":"habit","title":"Stretch"}""" to ActivitySummary(ActivityVerb.Converted)),
            // handlers/occurrences.rs reschedule seam
            ActivityActionKind.Rescheduled to
                (
                    """{"item_kind":"event","title":"Standup","from_date":"2026-07-20","to_date":"2026-07-22"}""" to
                        ActivitySummary(ActivityVerb.Rescheduled)
                    ),
            // handlers/comments.rs
            ActivityActionKind.CommentAdded to
                ("""{"comment_id":"c-1","is_private":false}""" to ActivitySummary(ActivityVerb.Commented)),
            ActivityActionKind.CommentEdited to
                ("""{"comment_id":"c-1","is_private":false}""" to ActivitySummary(ActivityVerb.CommentEdited)),
            ActivityActionKind.CommentDeleted to
                ("""{"comment_id":"c-1","is_private":false}""" to ActivitySummary(ActivityVerb.CommentDeleted)),
            // handlers/task_attachments.rs attachment_added_detail
            ActivityActionKind.AttachmentAdded to
                (
                    """{"item_kind":"task","attachments":[{"id":"a-1","filename":"shot.png"}]}""" to
                        ActivitySummary(ActivityVerb.AttachmentAdded)
                    ),
            ActivityActionKind.AttachmentDeleted to
                ("""{"item_kind":"task","attachments":[{"id":"a-1"}]}""" to ActivitySummary(ActivityVerb.AttachmentDeleted)),
            ActivityActionKind.AttachmentCaptioned to
                ("""{"item_kind":"task","attachment_id":"a-1"}""" to ActivitySummary(ActivityVerb.AttachmentCaptioned)),
            // handlers/items_plan.rs
            ActivityActionKind.PlanAdded to
                ("""{"date":"2026-07-20"}""" to ActivitySummary(ActivityVerb.PlanAdded)),
            ActivityActionKind.PlanRemoved to
                ("""{"date":"2026-07-20"}""" to ActivitySummary(ActivityVerb.PlanRemoved)),
            ActivityActionKind.PlanReordered to
                ("""{"date":"2026-07-20","order":["t-1","t-2"]}""" to ActivitySummary(ActivityVerb.PlanReordered)),
        )

        for ((kind, expectation) in cases) {
            val (detail, summary) = expectation
            assertEquals(summary, serverEntry(kind, detail = detail).summaryInfo(), "verb for ${kind.token}")
        }

        // A tripwire, not a proof: `serverSummary`'s `when` is exhaustive on the sealed hierarchy, so a new
        // verb is a COMPILE error in production — this size assertion is the nudge to add a row here too.
        assertEquals(18, cases.map { it.first }.toSet().size)
        // Every token above is one this build genuinely knows; a typo would silently decode to `Other`
        // and this table would then be asserting the fallback, not the mapping.
        assertTrue(cases.none { ActivityActionKind.fromToken(it.first.token) is ActivityActionKind.Other })
    }

    @Test
    fun aVerbThisBuildHasNeverHeardOfStillRendersThroughTheGenericPhrasing() {
        // A six-month-old build meeting a newer server. The forensic contract is "never drop an entry you
        // can't name" — so an unknown verb must render as a generic item update, not crash, and not blank.
        val kind = ActivityActionKind.fromToken("teleported")
        assertEquals(ActivityActionKind.Other("teleported"), kind)
        assertEquals("teleported", kind.token) // round-trips, so a later build can re-decode it precisely

        val entry = serverEntry(kind, detail = """{"item_kind":"task","destination":"mars"}""")
        // Generic on purpose: `Other` deliberately does NOT consult `item_kind`, because a verb we can't
        // name might not even be an item edit — "Updated a task" would be a claim we can't support.
        assertEquals(ActivitySummary(ActivityVerb.UpdatedItem), entry.summaryInfo())
        // …and it still deep-links, so an unnameable row is not a dead row.
        assertEquals("srv-1", entry.itemId())
    }

    // ── item_kind: task vs everything else ─────────────────────────────────────────────────────────

    @Test
    fun createdUpdatedAndDeletedTakeTheirItemKindFromTheDetailBlobNotTheOutboxTarget() {
        // `created` is kind-qualified for every kind — the summary line names it ("Created a chore").
        assertEquals(
            ActivitySummary(ActivityVerb.Created, "chore"),
            serverEntry(ActivityActionKind.Created, """{"title":"Vacuum","item_kind":"chore"}""").summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.Created, "event"),
            serverEntry(ActivityActionKind.Created, """{"title":"Standup","item_kind":"event"}""").summaryInfo(),
        )
        // Only Task has dedicated "updated a <kind>" / "deleted a <kind>" phrasing; the rest read as
        // "an item". Getting this backwards would render an untranslated or empty kind token.
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedTask),
            serverEntry(ActivityActionKind.Updated, """{"item_kind":"task","fields":{"pinned":{"old":false,"new":true}}}""").summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedItem),
            serverEntry(ActivityActionKind.Updated, """{"item_kind":"habit","fields":{"title":{"old":"a","new":"b"}}}""").summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.DeletedTask),
            serverEntry(ActivityActionKind.Deleted, """{"item_kind":"task","title":"Buy milk"}""").summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.DeletedItem),
            serverEntry(ActivityActionKind.Deleted, """{"item_kind":"chore","title":"Vacuum"}""").summaryInfo(),
        )
    }

    @Test
    fun aDetailBlobTheServerCouldNotUnsealFallsBackToTheGenericKindRatherThanBlanking() {
        // The server nulls `detail` when it can't unwrap the org DEK, and `toRemote` normalises that to
        // Kotlin null. The row must still render: the verb is plaintext, only the content was sealed.
        assertEquals(
            ActivitySummary(ActivityVerb.Created, "item"),
            serverEntry(ActivityActionKind.Created, detail = null).summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedItem),
            serverEntry(ActivityActionKind.Updated, detail = null).summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.DeletedItem),
            serverEntry(ActivityActionKind.Deleted, detail = null).summaryInfo(),
        )
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedOccurrence, "event"),
            serverEntry(ActivityActionKind.StatusChanged, detail = null, occurrence = "2026-07-20").summaryInfo(),
        )
        // A malformed blob must degrade the same way rather than throwing — a diagnostics screen that
        // crashes on one bad row is worse than one that under-describes it.
        assertEquals(
            ActivitySummary(ActivityVerb.Created, "item"),
            serverEntry(ActivityActionKind.Created, detail = "{not json").summaryInfo(),
        )
    }

    // ── status_changed is context-sensitive ────────────────────────────────────────────────────────

    @Test
    fun statusChangedReadsAsAnItemEditOnlyWhenTheEntryIsNotOccurrenceScoped() {
        // common.rs splits `status_changed` out of `updated` when status is the only field that moved.
        // Item-level (occurrence == null) is a "mark done" on the item itself.
        assertEquals(
            ActivitySummary(ActivityVerb.StatusChanged),
            serverEntry(
                ActivityActionKind.StatusChanged,
                detail = """{"item_kind":"task","title":"Buy milk","from":"todo","to":"done"}""",
                occurrence = null,
            ).summaryInfo(),
        )
        // The SAME verb with an `occurrence` is one firing of a recurring item being marked — the
        // pre-existing occurrence phrasing says that precisely. Rendering it as "changed the status" would
        // read as if the whole habit had been completed forever.
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedOccurrence, "habit"),
            serverEntry(
                ActivityActionKind.StatusChanged,
                detail = """{"item_kind":"habit","to":"done"}""",
                occurrence = "2026-07-20",
            ).summaryInfo(),
        )
        // …and `{"cleared": true}` is the occurrence-clear soft-delete, a distinct verb again.
        assertEquals(
            ActivitySummary(ActivityVerb.ClearedOccurrence, "chore"),
            serverEntry(
                ActivityActionKind.StatusChanged,
                detail = """{"item_kind":"chore","cleared":true}""",
                occurrence = "2026-07-20",
            ).summaryInfo(),
        )
        // A non-date occurrence key ("single", for a one-shot Event) is still occurrence-scoped — the
        // scoping test is presence, not shape, so a `single` row must not fall back to the item verb.
        assertEquals(
            ActivitySummary(ActivityVerb.UpdatedOccurrence, "event"),
            serverEntry(
                ActivityActionKind.StatusChanged,
                detail = """{"item_kind":"event","to":"done"}""",
                occurrence = "single",
            ).summaryInfo(),
        )
    }

    @Test
    fun theSystemDrivenAutoDropCascadeRendersLikeAnyOtherOccurrenceMark() {
        // occurrences.rs files the cascade auto-drop as a System-actor `status_changed` carrying
        // `{"auto": true}`. It has no human actor, but it is still a real ledger row the feed must render.
        val entry = serverEntry(
            ActivityActionKind.StatusChanged,
            detail = """{"item_kind":"chore","to":"dropped","auto":true}""",
            occurrence = "2026-07-19",
        ).copy(actorKind = ActivityActorKind.System, source = ActivitySource.System)

        assertEquals(ActivitySummary(ActivityVerb.UpdatedOccurrence, "chore"), entry.summaryInfo())
        // The `auto` flag is a hint about provenance, not a changed field — it must never become a diff row.
        assertEquals(listOf("status"), entry.changes().map { it.rawKey })
    }

    // ── the server-sourced diff ────────────────────────────────────────────────────────────────────

    @Test
    fun theUpdatedDetailOldNewPairsMapOntoTheRightBeforeAndAfterSides() {
        // `json_field_diff` emits `{field: {old, new}}`. Transposing the two sides is the single most
        // plausible bug here and would render every edit backwards in the detail sheet.
        val change = serverEntry(
            ActivityActionKind.Updated,
            detail = """{"item_kind":"task","fields":{"title":{"old":"a","new":"b"}}}""",
        ).changes().single()

        assertEquals(ActivityField.Title, change.field)
        assertEquals("title", change.rawKey)
        assertEquals(ActivityFieldValue.Present("a"), change.before)
        assertEquals(ActivityFieldValue.Present("b"), change.after)
    }

    @Test
    fun theUpdatedDetailKeepsTheServersSortedKeyOrderAndReadsWireNullsAsCleared() {
        // json_field_diff sorts the key union before building `fields`, so the order is stable across
        // pages — the detail sheet must not reshuffle rows when a later page re-delivers the same entry.
        val changes = serverEntry(
            ActivityActionKind.Updated,
            detail = """
                {"item_kind":"task","fields":{
                  "complete_by":{"old":"2026-07-15T00:00:00Z","new":null},
                  "description":{"old":null,"new":"a note"},
                  "labels":{"old":["home"],"new":["home","errand"]}
                }}
            """.trimIndent(),
        ).changes()

        assertEquals(listOf(ActivityField.Deadline, ActivityField.Description, ActivityField.Labels), changes.map { it.field })
        // A wire null on either side is an explicit empty ("cleared"), NOT "we didn't capture it" — the
        // View renders those two very differently and only Cleared means the user emptied the field.
        assertEquals(ActivityFieldValue.Present("2026-07-15T00:00:00Z"), changes[0].before)
        assertEquals(ActivityFieldValue.Cleared, changes[0].after)
        assertEquals(ActivityFieldValue.Cleared, changes[1].before)
        assertEquals(ActivityFieldValue.Present("a note"), changes[1].after)
        assertEquals(ActivityFieldValue.Present("home"), changes[2].before)
        assertEquals(ActivityFieldValue.Present("home, errand"), changes[2].after)
    }

    @Test
    fun theStatusChangedDetailCollapsesToExactlyOneStatusChange() {
        // `{from,to}` sits at the TOP level of this blob, not under `fields` — reusing the `updated`
        // reader here would yield an empty diff and a status row that says nothing.
        val change = serverEntry(
            ActivityActionKind.StatusChanged,
            detail = """{"item_kind":"task","title":"Buy milk","from":"todo","to":"done"}""",
        ).changes().single()

        assertEquals(ActivityField.Status, change.field)
        assertEquals("status", change.rawKey)
        assertEquals(ActivityFieldValue.Present("todo"), change.before)
        assertEquals(ActivityFieldValue.Present("done"), change.after)
        // `item_kind` and `title` are envelope metadata the summary already uses — surfacing them as
        // changed fields would put two bogus rows in the sheet.
        assertEquals(1, serverEntry(ActivityActionKind.StatusChanged, """{"item_kind":"task","title":"t","from":"todo","to":"done"}""").changes().size)
    }

    @Test
    fun anOccurrenceMarkRendersOnlyItsNewStatusWhileAClearRendersNoDiffAtAll() {
        // occurrences.rs writes `{"to": …}` for a mark and `{"cleared": true}` for a clear — the server
        // never reads back the previous occurrence status, so there is no `from` to render.
        val mark = serverEntry(
            ActivityActionKind.StatusChanged,
            detail = """{"item_kind":"habit","to":"done"}""",
            occurrence = "2026-07-20",
        ).changes().single()
        assertEquals(ActivityField.Status, mark.field)
        // Unavailable, not Cleared: the old status wasn't captured, which is not the same as "was empty".
        assertEquals(ActivityFieldValue.Unavailable, mark.before)
        assertEquals(ActivityFieldValue.Present("done"), mark.after)

        // A clear carries neither side, so it renders no diff — its verb already says what happened.
        assertTrue(
            serverEntry(
                ActivityActionKind.StatusChanged,
                detail = """{"item_kind":"chore","cleared":true}""",
                occurrence = "2026-07-20",
            ).changes().isEmpty(),
        )
    }

    @Test
    fun everyVerbBesidesUpdatedAndStatusChangedRendersNoDiffHoweverRichItsDetailIs() {
        // These blobs are full of keys (`from_parent_id`, `merged_count`, `attachments`, `order`…) that are
        // NOT user-edited fields. Feeding them through the diff reader would fill the detail sheet with
        // rows like "merged_count: — -> 2", which is noise dressed up as an edit.
        val richButDiffless = listOf(
            ActivityActionKind.Created to """{"title":"Buy milk","item_kind":"task"}""",
            ActivityActionKind.Deleted to """{"item_kind":"task","title":"Buy milk"}""",
            ActivityActionKind.Moved to """{"item_kind":"task","title":"t","from_parent_id":"p-1","to_parent_id":"p-2","position":3}""",
            ActivityActionKind.Merged to """{"item_kind":"task","surviving_title":"t","merged_source_ids":["t-2"],"merged_count":1}""",
            ActivityActionKind.Converted to """{"from_kind":"task","to_kind":"habit","title":"Stretch"}""",
            ActivityActionKind.Rescheduled to """{"item_kind":"event","title":"Standup","from_date":"2026-07-20","to_date":"2026-07-22"}""",
            ActivityActionKind.Split to """{"title":"Stretch","item_kind":"habit","predecessor_id":"h-0","split_at":"2026-07-20T00:00:00Z"}""",
            ActivityActionKind.CommentAdded to """{"comment_id":"c-1","is_private":false}""",
            ActivityActionKind.AttachmentAdded to """{"item_kind":"task","attachments":[{"id":"a-1"}]}""",
            ActivityActionKind.PlanReordered to """{"date":"2026-07-20","order":["t-1","t-2"]}""",
            ActivityActionKind.Other("teleported") to """{"item_kind":"task","destination":"mars"}""",
        )
        for ((kind, detail) in richButDiffless) {
            assertTrue(serverEntry(kind, detail = detail).changes().isEmpty(), "diff for ${kind.token}")
        }
    }

    @Test
    fun anAbsentOrMalformedDetailYieldsAnEmptyDiffRatherThanThrowing() {
        assertTrue(serverEntry(ActivityActionKind.Updated, detail = null).changes().isEmpty())
        assertTrue(serverEntry(ActivityActionKind.Updated, detail = "{not json").changes().isEmpty())
        // A well-formed blob missing the `fields` object (an older server, or a future reshaping).
        assertTrue(serverEntry(ActivityActionKind.Updated, detail = """{"item_kind":"task"}""").changes().isEmpty())
        // …and a `fields` entry that isn't an old/new object at all still yields a row, both sides absent,
        // rather than blowing up the whole page over one malformed key.
        val change = serverEntry(ActivityActionKind.Updated, detail = """{"fields":{"title":"just a string"}}""").changes().single()
        assertEquals(ActivityFieldValue.Unavailable, change.before)
        assertEquals(ActivityFieldValue.Unavailable, change.after)
    }

    // ── precedence: the local capture beats the server's detail ────────────────────────────────────

    @Test
    fun aLocallyCapturedBodyAndBeforeWinOverTheServerDetailOnTheSameReconciledRow() {
        // Once the reconcile lands, ONE row carries both halves: this device's captured request (what it
        // actually sent) and the server's whitelisted snapshot. The local capture wins — it can hold fields
        // the server's whitelist never snapshots, so preferring `detail` would silently drop them.
        val merged = serverEntry(
            ActivityActionKind.Updated,
            detail = """{"item_kind":"task","fields":{"title":{"old":"server-old","new":"server-new"}}}""",
            target = "task:srv-1",
            method = OutboxMethod.Patch,
            body = """{"title":"local-new","pinned":true}""",
            before = """{"title":"local-old","pinned":false}""",
        )

        val changes = merged.changes()
        assertEquals(listOf("title", "pinned"), changes.map { it.rawKey }) // body order, and `pinned` survives
        assertEquals(ActivityFieldValue.Present("local-old"), changes[0].before)
        assertEquals(ActivityFieldValue.Present("local-new"), changes[0].after)

        // Precedence is per-derivation, not per-row: the SUMMARY still comes from the server's verb even
        // though the diff came from the local capture. Both halves of the row stay useful.
        assertEquals(ActivitySummary(ActivityVerb.UpdatedTask), merged.summaryInfo())
    }

    @Test
    fun aLocalCaptureOfOnlyTheBeforeImageStillOutranksTheServerDetail() {
        // A delete-shaped write snapshots a before-image with no body. That is still a local capture, so it
        // must take precedence — otherwise a half-captured row would flip mid-way to the server's values.
        val changes = serverEntry(
            ActivityActionKind.Updated,
            detail = """{"item_kind":"task","fields":{"title":{"old":"server-old","new":"server-new"}}}""",
            target = "task:srv-1",
            method = OutboxMethod.Patch,
            before = """{"title":"local-old"}""",
        ).changes()

        val change = changes.single()
        assertEquals(ActivityFieldValue.Present("local-old"), change.before)
        assertEquals(ActivityFieldValue.Unavailable, change.after)
    }

    @Test
    fun theActivityStampMergedIntoTheOutgoingBodyIsNeverRenderedAsAChangedField() {
        // The outbox choke-point merges the client-minted `activity: {id, at, source}` stamp into the body
        // that goes on the wire, so it shows up in EVERY captured body. It is metadata about the change,
        // not a field the user changed — surfacing it would put a bogus "activity" row in every detail
        // sheet (and leak the entry id into the UI).
        val entry = serverEntry(
            ActivityActionKind.Updated,
            detail = null,
            target = "task:srv-1",
            method = OutboxMethod.Patch,
            body = """{"title":"New","activity":{"id":"e-1","at":"2026-07-20T09:00:00Z","source":"mobile"}}""",
            before = """{"title":"Old"}""",
        )

        val change = entry.changes().single()
        assertEquals(ActivityField.Title, change.field)
        assertEquals(ActivityFieldValue.Present("Old"), change.before)
        assertEquals(ActivityFieldValue.Present("New"), change.after)
        assertFalse(entry.changes().any { it.rawKey == "activity" })
    }

    @Test
    fun aBodyThatCarriesNothingButTheStampRendersAnEmptyDiffNotAStampRow() {
        // The stamp rides even on routes whose body is pure intent (an occurrence clear, say). Filtering it
        // must leave an EMPTY diff, so the View falls back to the coarse summary rather than showing one
        // meaningless row.
        val entry = serverEntry(
            ActivityActionKind.StatusChanged,
            detail = null,
            occurrence = "2026-07-20",
            target = "occurrence:Chore:c-1:2026-07-20",
            method = OutboxMethod.Post,
            body = """{"activity":{"id":"e-1","at":"2026-07-20T09:00:00Z","source":"mobile"}}""",
        )
        assertTrue(entry.changes().isEmpty())
    }

    // ── deep linking ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun itemIdPrefersTheServerIdOverTheOutboxTargetOnAReconciledRow() {
        // The server id is authoritative: a move/convert/split can retarget the row onto a different item
        // than the one this device addressed, and the deep link must follow the server.
        assertEquals(
            "srv-1",
            serverEntry(ActivityActionKind.Updated, serverItemId = "srv-1", target = "task:local-2", method = OutboxMethod.Patch).itemId(),
        )
        // A purely-local row (no server half yet) still resolves through the outbox target, so an
        // un-reconciled write is deep-linkable the instant it is made.
        assertEquals(
            "local-2",
            serverEntry(ActivityActionKind.Updated, serverItemId = null, target = "task:local-2", method = OutboxMethod.Patch).itemId(),
        )
    }

    @Test
    fun planReorderedRefusesToDeepLinkBecauseItsItemIdIsAnOrgSentinel() {
        // items_plan.rs files a whole-plan reorder against `user.personal_org_id` — there is no single item
        // target for it. Taking that id at face value would deep-link into a task detail screen for an ORG
        // uuid: a guaranteed "not found", from a row that looked perfectly clickable.
        assertNull(
            serverEntry(
                ActivityActionKind.PlanReordered,
                detail = """{"date":"2026-07-20","order":["t-1","t-2"]}""",
                serverItemId = "org-personal-uuid",
                occurrence = "2026-07-20",
            ).itemId(),
        )
        // The filter is narrow on purpose — the other two plan verbs ARE filed against the real task, so
        // widening this to "any plan verb" would break two working deep links to fix one broken one.
        assertEquals(
            "t-1",
            serverEntry(ActivityActionKind.PlanAdded, """{"date":"2026-07-20"}""", serverItemId = "t-1", occurrence = "2026-07-20").itemId(),
        )
        assertEquals(
            "t-1",
            serverEntry(ActivityActionKind.PlanRemoved, """{"date":"2026-07-20"}""", serverItemId = "t-1", occurrence = "2026-07-20").itemId(),
        )
    }

    // ── the three time axes ────────────────────────────────────────────────────────────────────────

    @Test
    fun displayAtFollowsTheActorsClockAndFallsBackToTheLocalApplyTime() {
        // The offline case: acted at 09:00, reconciled/applied at 17:00. The feed must show 09:00 or a
        // day's offline work all lands in one lump at flush time, in the wrong order.
        assertEquals(occurred, serverEntry(ActivityActionKind.Updated).displayAt)
        // A row written before #364 has no occurredAt at all; falling back to recordedAt keeps it in its
        // existing feed position instead of sinking it to the epoch.
        assertEquals(recorded, serverEntry(ActivityActionKind.Updated, occurredAt = null).displayAt)
    }

    @Test
    fun isAcknowledgedFlipsOnlyOnceTheServerClockHasBeenStamped() {
        // observedAt is the marker that an authoritative twin exists. Un-acknowledged means "optimistic,
        // not yet reconciled" — NOT "failed" — so it must not be inferred from occurredAt or recordedAt,
        // both of which a purely local row already has.
        assertFalse(serverEntry(ActivityActionKind.Updated, observedAt = null).isAcknowledged)
        assertTrue(serverEntry(ActivityActionKind.Updated).isAcknowledged)
        assertTrue(serverEntry(ActivityActionKind.Updated, occurredAt = null).isAcknowledged)
    }
}

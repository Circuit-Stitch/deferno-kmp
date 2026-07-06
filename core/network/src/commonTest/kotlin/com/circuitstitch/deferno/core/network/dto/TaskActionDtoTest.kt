package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Decode tests for [TaskActionDto] / [TaskActionKind] — the server-authored per-item history
 * (`actions[]`, `GET /items/{id}/history`). `TaskActionKind` is Serde's **externally-tagged** enum:
 * unit variants are bare JSON strings (`"Created"`), data variants are single-key objects
 * (`{"StatusChanged": {…}}`). These pin the hand-written serializer against that wire shape through
 * the shipping tolerant reader ([DefernoJson]), incl. the Unknown-degrade for additive server kinds.
 */
class TaskActionDtoTest {

    private fun decode(json: String): TaskActionDto =
        DefernoJson.decodeFromString(TaskActionDto.serializer(), json)

    @Test
    fun createdDecodesFromABareString() {
        val dto = decode("""{"kind":"Created","recorded_at":"2026-01-02T03:04:05Z"}""")

        assertEquals(TaskActionKind.Created, dto.kind)
        assertEquals("2026-01-02T03:04:05Z", dto.recordedAt)
    }

    @Test
    fun anUnknownBareStringKindDegradesToUnknown() {
        // An additive server unit-variant token must not crash the tolerant reader.
        val dto = decode("""{"kind":"Vaporized","recorded_at":"2026-01-02T03:04:05Z"}""")

        assertEquals(TaskActionKind.Unknown, dto.kind)
    }

    @Test
    fun mergedIntoParentDecodesFromTheSecondBareString() {
        val dto = decode("""{"kind":"MergedIntoParent","recorded_at":"2026-01-02T03:04:05Z"}""")

        assertEquals(TaskActionKind.MergedIntoParent, dto.kind)
    }

    @Test
    fun updatedDecodesItsFieldsFromASingleKeyObject() {
        val dto = decode(
            """{"kind":{"Updated":{"fields":["title","desire"]}},"recorded_at":"2026-01-02T03:04:05Z"}""",
        )

        assertEquals(TaskActionKind.Updated(listOf("title", "desire")), dto.kind)
    }

    @Test
    fun statusChangedDecodesFromAndToWorkingStatuses() {
        val dto = decode(
            """{"kind":{"StatusChanged":{"from":"open","to":"done"}},"recorded_at":"2026-01-02T03:04:05Z"}""",
        )

        assertEquals(TaskActionKind.StatusChanged(TaskStatusWire.Open, TaskStatusWire.Done), dto.kind)
    }

    @Test
    fun statusChangedDegradesAnOutOfVocabularyStatusToUnknownRatherThanThrowing() {
        // /items/{id}/history serves all kinds; a recurring item's StatusChanged carries DefStatus
        // tokens (active/archived) TaskStatusWire can't name. The required-no-default wire field would
        // throw and abort the whole array — the Unknown default must rescue it (ADR-0043).
        val dto = decode(
            """{"kind":{"StatusChanged":{"from":"active","to":"archived"}},"recorded_at":"2026-01-02T03:04:05Z"}""",
        )

        assertEquals(TaskActionKind.StatusChanged(TaskStatusWire.Unknown, TaskStatusWire.Unknown), dto.kind)
    }

    @Test
    fun parentAssignedDecodesItsParentId() {
        val dto = decode(
            """{"kind":{"ParentAssigned":{"parent_id":"p-1"}},"recorded_at":"2026-01-02T03:04:05Z"}""",
        )

        assertEquals(TaskActionKind.ParentAssigned("p-1"), dto.kind)
    }

    @Test
    fun splitAndFoldedIntoAndMergedChildDecodeTheirPeerIds() {
        assertEquals(
            TaskActionKind.Split("c-1"),
            decode("""{"kind":{"Split":{"child_id":"c-1"}},"recorded_at":"2026-01-02T03:04:05Z"}""").kind,
        )
        assertEquals(
            TaskActionKind.FoldedInto("n-1"),
            decode("""{"kind":{"FoldedInto":{"next_task_id":"n-1"}},"recorded_at":"2026-01-02T03:04:05Z"}""").kind,
        )
        assertEquals(
            TaskActionKind.MergedChild("c-2"),
            decode("""{"kind":{"MergedChild":{"child_id":"c-2"}},"recorded_at":"2026-01-02T03:04:05Z"}""").kind,
        )
    }

    @Test
    fun movedDecodesItsNullablePeerIdsAndPosition() {
        // Only to_parent_id present → from_parent_id + position default to null (all Moved fields are
        // nullable on the wire).
        val dto = decode(
            """{"kind":{"Moved":{"to_parent_id":"p-2"}},"recorded_at":"2026-01-02T03:04:05Z"}""",
        )

        assertEquals(TaskActionKind.Moved(fromParentId = null, toParentId = "p-2", position = null), dto.kind)
    }

    @Test
    fun anUnknownSingleKeyObjectKindDegradesToUnknown() {
        val dto = decode("""{"kind":{"Teleported":{"whither":"void"}},"recorded_at":"2026-01-02T03:04:05Z"}""")

        assertEquals(TaskActionKind.Unknown, dto.kind)
    }
}

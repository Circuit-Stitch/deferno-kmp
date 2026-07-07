package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decode tests for [TaskActionDto] / [TaskActionKind] — the server-authored per-item history
 * (`actions[]`, `GET /items/{id}/history`). `TaskActionKind` is Serde's **externally-tagged** enum:
 * unit variants are bare JSON strings (`"Created"`), data variants are single-key objects
 * (`{"StatusChanged": {…}}`). The DTO carries `kind` as the raw wire element (the cache stores it
 * verbatim); these pin [toTaskActionKind] against that wire shape through the shipping tolerant reader
 * ([DefernoJson]), incl. the Unknown-degrade for additive server kinds.
 */
class TaskActionDtoTest {

    private fun decode(json: String): TaskActionDto =
        DefernoJson.decodeFromString(TaskActionDto.serializer(), json)

    /** Decode a full history entry and crack its raw wire kind — the lazy read path the cache uses. */
    private fun kind(json: String): TaskActionKind = decode(json).kind.toTaskActionKind(DefernoJson)

    @Test
    fun createdDecodesFromABareString() {
        val dto = decode("""{"kind":"Created","recorded_at":"2026-01-02T03:04:05Z"}""")

        assertEquals(TaskActionKind.Created, dto.kind.toTaskActionKind(DefernoJson))
        assertEquals("2026-01-02T03:04:05Z", dto.recordedAt)
    }

    @Test
    fun anUnknownBareStringKindDegradesToUnknown() {
        // An additive server unit-variant token must not crash the tolerant reader.
        assertEquals(TaskActionKind.Unknown, kind("""{"kind":"Vaporized","recorded_at":"2026-01-02T03:04:05Z"}"""))
    }

    @Test
    fun mergedIntoParentDecodesFromTheSecondBareString() {
        assertEquals(
            TaskActionKind.MergedIntoParent,
            kind("""{"kind":"MergedIntoParent","recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun updatedDecodesItsFieldsFromASingleKeyObject() {
        assertEquals(
            TaskActionKind.Updated(listOf("title", "desire")),
            kind("""{"kind":{"Updated":{"fields":["title","desire"]}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun statusChangedDecodesFromAndToWorkingStatuses() {
        assertEquals(
            TaskActionKind.StatusChanged(TaskStatusWire.Open, TaskStatusWire.Done),
            kind("""{"kind":{"StatusChanged":{"from":"open","to":"done"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun statusChangedDegradesAnOutOfVocabularyStatusToUnknownRatherThanThrowing() {
        // /items/{id}/history serves all kinds; a recurring item's StatusChanged carries DefStatus
        // tokens (active/archived) TaskStatusWire can't name. The required-no-default wire field would
        // throw and abort the whole array — the Unknown default must rescue it (ADR-0043).
        assertEquals(
            TaskActionKind.StatusChanged(TaskStatusWire.Unknown, TaskStatusWire.Unknown),
            kind("""{"kind":{"StatusChanged":{"from":"active","to":"archived"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun parentAssignedDecodesItsParentId() {
        assertEquals(
            TaskActionKind.ParentAssigned("p-1"),
            kind("""{"kind":{"ParentAssigned":{"parent_id":"p-1"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun splitAndFoldedIntoAndMergedChildDecodeTheirPeerIds() {
        assertEquals(
            TaskActionKind.Split("c-1"),
            kind("""{"kind":{"Split":{"child_id":"c-1"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
        assertEquals(
            TaskActionKind.FoldedInto("n-1"),
            kind("""{"kind":{"FoldedInto":{"next_task_id":"n-1"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
        assertEquals(
            TaskActionKind.MergedChild("c-2"),
            kind("""{"kind":{"MergedChild":{"child_id":"c-2"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun movedDecodesItsNullablePeerIdsAndPosition() {
        // Only to_parent_id present → from_parent_id + position default to null (all Moved fields are
        // nullable on the wire).
        assertEquals(
            TaskActionKind.Moved(fromParentId = null, toParentId = "p-2", position = null),
            kind("""{"kind":{"Moved":{"to_parent_id":"p-2"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun anUnknownSingleKeyObjectKindDegradesToUnknown() {
        assertEquals(
            TaskActionKind.Unknown,
            kind("""{"kind":{"Teleported":{"whither":"void"}},"recorded_at":"2026-01-02T03:04:05Z"}"""),
        )
    }

    @Test
    fun theCacheStoresTheRawWireKindVerbatimSoAnUnknownStaysRecoverable() {
        // The cache payload is the raw wire kind (kind.toString()), not a re-encoded/degraded value: an
        // additive server kind keeps its original bytes, and re-parsing decodes identically (Unknown for
        // now, but a future client that learns the kind can recover it — the ADR-0043 raw-cache win).
        val raw = decode("""{"kind":{"Teleported":{"whither":"void"}},"recorded_at":"2026-01-02T03:04:05Z"}""").kind.toString()

        assertTrue(raw.contains("Teleported"), "raw kind bytes preserved, not frozen to \"Unknown\": $raw")
        assertEquals(TaskActionKind.Unknown, DefernoJson.parseToJsonElement(raw).toTaskActionKind(DefernoJson))
    }
}

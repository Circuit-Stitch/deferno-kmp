package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The faithful flat wire DTOs (ADR-0011) parse the EXACT staging wire shapes captured in
 * `contracts/fixtures/`. The JSON is embedded inline (copied faithfully from the fixtures) so the
 * test is hermetic — the fixtures directory is not on the test classpath. Decoded with the
 * project's tolerant reader ([DefernoJson]) so the assertions reflect the shipping configuration
 * (`ignoreUnknownKeys`, `coerceInputValues`, defaults).
 */
class WireDtoDecodeTest {

    private fun <T> decodeData(serializer: kotlinx.serialization.KSerializer<T>, json: String): T =
        DefernoJson.decodeFromString(Envelope.serializer(serializer), json).data

    // --- TaskSummaryDto from tasks-sample.json (the /tasks list shape) ---

    private val tasksSample = """
        {
          "version": "0.1",
          "data": [
            {
              "id": "7033cae7-eff6-4df1-bed9-01d16e89c2b0",
              "title": "<title>",
              "status": "dropped",
              "labels": ["family"],
              "parent_id": null,
              "children": [],
              "complete_by": "2026-04-10T07:45:00Z",
              "productive": -0.45,
              "desire": null,
              "date_created": "2026-04-10T17:47:32.694061553Z",
              "pinned": false,
              "ref": "u-e4h2qk-1",
              "org_slug": "u-e4h2qk",
              "sequence": 1,
              "type": "task"
            },
            {
              "id": "3e218381-e57f-48fc-a078-c34632c662c0",
              "title": "<title>",
              "status": "dropped",
              "labels": ["mental health"],
              "parent_id": null,
              "children": [
                "461dfe4c-90db-4220-a93b-c645ed075688",
                "085a5db8-2b15-4cf6-a28c-dd194538b516"
              ],
              "complete_by": "2026-04-14T06:59:00Z",
              "productive": 0.25,
              "desire": -0.05,
              "date_created": "2026-04-10T18:25:12.380794780Z",
              "pinned": false,
              "descendant_done": 1,
              "descendant_total": 2,
              "ref": "u-e4h2qk-4",
              "org_slug": "u-e4h2qk",
              "sequence": 4,
              "type": "task"
            },
            {
              "id": "948bcfab-063d-4499-b2de-f21801bc6f9c",
              "title": "<title>",
              "status": "open",
              "labels": [],
              "parent_id": null,
              "children": [
                "4c9215e2-111b-4727-af2f-f4b9456cb0ff",
                "bffc4836-5899-4c47-abcc-90181bff5d68"
              ],
              "complete_by": null,
              "productive": null,
              "desire": null,
              "date_created": "2026-05-20T16:11:42.625684725Z",
              "pinned": true,
              "descendant_done": 0,
              "descendant_total": 5,
              "ref": "u-e4h2qk-311",
              "org_slug": "u-e4h2qk",
              "sequence": 311,
              "type": "task"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesTasksSampleIntoTaskSummaryDtos() {
        val summaries = decodeData(ListSerializer(TaskSummaryDto.serializer()), tasksSample)
        assertEquals(3, summaries.size)

        val first = summaries[0]
        assertEquals("7033cae7-eff6-4df1-bed9-01d16e89c2b0", first.id)
        assertEquals(TaskStatusWire.Dropped, first.status)
        assertEquals(listOf("family"), first.labels)
        assertNull(first.parentId)
        assertTrue(first.children.isEmpty())
        assertEquals("2026-04-10T07:45:00Z", first.completeBy)
        assertEquals(-0.45, first.productive)
        assertNull(first.desire)
        assertEquals("u-e4h2qk-1", first.ref)
        assertEquals("u-e4h2qk", first.orgSlug)
        assertEquals(1L, first.sequence)
        assertEquals("task", first.type)
        assertEquals(false, first.pinned)
        assertNull(first.deletedAt)

        val second = summaries[1]
        assertEquals(2, second.children.size)
        assertEquals(1, second.descendantDone)
        assertEquals(2, second.descendantTotal)

        val third = summaries[2]
        assertEquals(TaskStatusWire.Open, third.status)
        assertEquals(true, third.pinned)
    }

    // --- TaskSummaryDto from plan.json — note entries that OMIT ref + sequence ---

    private val planSample = """
        {
          "version": "0.1",
          "data": [
            {
              "id": "ba3f4471-f525-4ce6-bc74-c6cfdb88593b",
              "title": "<title>",
              "status": "open",
              "labels": ["personal"],
              "parent_id": "bffc4836-5899-4c47-abcc-90181bff5d68",
              "children": [],
              "complete_by": null,
              "productive": null,
              "desire": null,
              "date_created": "2026-04-13T06:39:06.638075961Z",
              "pinned": false,
              "ref": "u-e4h2qk-90",
              "org_slug": "u-e4h2qk",
              "sequence": 90,
              "type": "task"
            },
            {
              "id": "bea3f060-d0ec-4025-93cd-ee9f0649d056",
              "title": "<title>",
              "status": "open",
              "labels": [],
              "parent_id": null,
              "children": [],
              "complete_by": null,
              "productive": null,
              "desire": null,
              "date_created": "2026-06-06T09:16:39.915498553Z",
              "pinned": false,
              "org_slug": "u-e4h2qk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesPlanSampleIncludingEntriesThatOmitRefAndSequence() {
        val summaries = decodeData(ListSerializer(TaskSummaryDto.serializer()), planSample)
        assertEquals(2, summaries.size)

        val withRef = summaries[0]
        assertEquals("u-e4h2qk-90", withRef.ref)
        assertEquals(90L, withRef.sequence)
        assertEquals("bffc4836-5899-4c47-abcc-90181bff5d68", withRef.parentId)

        // The brand-new entry: ref + sequence + type absent on the wire → nullable / default.
        val withoutRef = summaries[1]
        assertNull(withoutRef.ref)
        assertNull(withoutRef.sequence)
        assertEquals("u-e4h2qk", withoutRef.orgSlug)
    }

    // --- TaskDetailDto: the `task`-typed element of items-sample.json ---

    private val taskDetailSample = """
        {
          "version": "0.1",
          "data": {
            "actions": [
              { "kind": "Created", "recorded_at": "2026-05-20T16:11:42.625684725Z" },
              { "kind": { "Updated": { "fields": ["pinned"] } }, "recorded_at": "2026-05-20T16:11:45.610131742Z" }
            ],
            "assignee": null,
            "attachments": [],
            "children": [
              "4c9215e2-111b-4727-af2f-f4b9456cb0ff",
              "bffc4836-5899-4c47-abcc-90181bff5d68"
            ],
            "comment": [],
            "complete_by": null,
            "created_by": "1d35f62e-eed9-44de-96e8-e61a307af83f",
            "date_created": "2026-05-20T16:11:42.625684725Z",
            "description": "<description>",
            "desire": null,
            "finished_at": null,
            "group_id": null,
            "id": "948bcfab-063d-4499-b2de-f21801bc6f9c",
            "kind": "task",
            "labels": [],
            "mood_finish": null,
            "mood_start": null,
            "next_task_id": null,
            "org_slug": "u-e4h2qk",
            "owner_org_id": "ebca93e5-d663-4624-9fe9-c5361b5b4390",
            "parent_id": null,
            "pinned": true,
            "productive": null,
            "ref": "u-e4h2qk-311",
            "sequence": 311,
            "status": "open",
            "title": "<title>",
            "type": "task"
          }
        }
    """.trimIndent()

    @Test
    fun decodesTaskDetailFromItemsSample() {
        val detail = decodeData(TaskDetailDto.serializer(), taskDetailSample)
        assertEquals("948bcfab-063d-4499-b2de-f21801bc6f9c", detail.id)
        assertEquals("u-e4h2qk", detail.orgSlug)
        assertEquals("ebca93e5-d663-4624-9fe9-c5361b5b4390", detail.ownerOrgId)
        assertEquals("u-e4h2qk-311", detail.ref)
        assertEquals(311L, detail.sequence)
        assertEquals(TaskStatusWire.Open, detail.status)
        assertEquals(2, detail.children.size)
        assertEquals(true, detail.pinned)
        assertEquals("<description>", detail.description)
        assertNull(detail.nextTaskId)
        assertNull(detail.finishedAt)
        assertNull(detail.deletedAt)
        assertEquals("task", detail.type)
    }

    // --- TodayTaskDto from today-sample.json (nested `task`) ---

    private val todaySample = """
        {
          "version": "0.1",
          "data": [
            {
              "task": {
                "id": "7ac8513c-4037-43fe-a89a-721019f76527",
                "title": "<title>",
                "status": "open",
                "labels": ["family"],
                "parent_id": null,
                "children": [],
                "complete_by": "2026-04-26T06:59:00Z",
                "productive": null,
                "desire": null,
                "date_created": "2026-04-18T20:08:13.919016817Z",
                "pinned": true
              },
              "priority_score": 55.0,
              "urgency_reason": "overdue",
              "ref": "u-e4h2qk-123",
              "org_slug": "u-e4h2qk",
              "sequence": 123,
              "type": "task"
            },
            {
              "task": {
                "id": "91118bfd-3eec-4871-a2cf-b693baf65049",
                "title": "<title>",
                "status": "open",
                "labels": ["house"],
                "parent_id": "4e255ace-2007-47f5-a599-4c068ed0ba1d",
                "children": [],
                "complete_by": "2026-04-16T06:59:00Z",
                "productive": 0.61,
                "desire": -0.17,
                "date_created": "2026-04-11T16:59:48.112595896Z",
                "pinned": false
              },
              "priority_score": 55.0,
              "urgency_reason": "overdue",
              "ref": "u-e4h2qk-38",
              "org_slug": "u-e4h2qk",
              "sequence": 38,
              "type": "task"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesTodaySample() {
        val today = decodeData(ListSerializer(TodayTaskDto.serializer()), todaySample)
        assertEquals(2, today.size)

        val first = today[0]
        assertEquals("7ac8513c-4037-43fe-a89a-721019f76527", first.task.id)
        assertEquals(55.0, first.priorityScore)
        assertEquals("overdue", first.urgencyReason)
        assertEquals("u-e4h2qk-123", first.ref)
        assertEquals(123L, first.sequence)
        assertEquals(TaskStatusWire.Open, first.task.status)
        // The nested summary omits ref/sequence — they live on the envelope wrapper.
        assertNull(first.task.ref)
    }

    // --- ItemView sealed polymorphism: all four kinds of items-sample.json ---

    private val itemsSample = """
        {
          "version": "0.1",
          "data": [
            {
              "actions": [],
              "assignee": null,
              "attachments": [],
              "children": ["4c9215e2-111b-4727-af2f-f4b9456cb0ff"],
              "comment": [],
              "complete_by": null,
              "created_by": "1d35f62e-eed9-44de-96e8-e61a307af83f",
              "date_created": "2026-05-20T16:11:42.625684725Z",
              "description": "<description>",
              "desire": null,
              "finished_at": null,
              "group_id": null,
              "id": "948bcfab-063d-4499-b2de-f21801bc6f9c",
              "kind": "task",
              "labels": [],
              "next_task_id": null,
              "org_slug": "u-e4h2qk",
              "owner_org_id": "ebca93e5-d663-4624-9fe9-c5361b5b4390",
              "parent_id": null,
              "pinned": true,
              "productive": null,
              "ref": "u-e4h2qk-311",
              "sequence": 311,
              "status": "open",
              "title": "<title>",
              "type": "task"
            },
            {
              "actions": [],
              "children": [],
              "complete_by": "2026-05-04T01:52:59Z",
              "created_by": "1d35f62e-eed9-44de-96e8-e61a307af83f",
              "date_created": "2026-05-04T01:53:05.597388900Z",
              "description": "<description>",
              "desire": null,
              "group_id": null,
              "id": "77dd6a6e-b936-4f61-9807-c3a6b647f9f1",
              "kind": "habit",
              "labels": [],
              "org_slug": "u-e4h2qk",
              "owner_org_id": "ebca93e5-d663-4624-9fe9-c5361b5b4390",
              "parent_id": null,
              "pinned": false,
              "productive": null,
              "recurrence": { "type": "daily" },
              "ref": "u-e4h2qk-185",
              "sequence": 185,
              "series_id": "b7c21959-c5f6-4087-8ab2-7690c81e463a",
              "status": "active",
              "subtask_template": [],
              "title": "<title>",
              "type": "habit"
            },
            {
              "actions": [],
              "cadence_mode": "rolling",
              "children": [],
              "complete_by": "2026-05-12T18:59:59Z",
              "created_by": "1d35f62e-eed9-44de-96e8-e61a307af83f",
              "date_created": "2026-05-12T19:52:01.762747577Z",
              "description": "<description>",
              "desire": null,
              "group_id": null,
              "id": "47338a14-a07f-4ddf-ad73-f5edc977dab0",
              "kind": "chore",
              "labels": ["medical"],
              "org_slug": "u-e4h2qk",
              "owner_org_id": "ebca93e5-d663-4624-9fe9-c5361b5b4390",
              "parent_id": null,
              "pinned": false,
              "productive": null,
              "recurrence": { "days": ["Tue"], "type": "weekly" },
              "ref": "u-e4h2qk-277",
              "sequence": 277,
              "series_id": "e0806009-9fd6-4ba1-8c78-d45415424d11",
              "status": "active",
              "subtask_template": [],
              "title": "<title>",
              "type": "chore"
            },
            {
              "actions": [],
              "all_day": false,
              "children": [],
              "complete_by": "2026-04-18T16:00:00Z",
              "created_by": "1d35f62e-eed9-44de-96e8-e61a307af83f",
              "date_created": "2026-05-02T15:00:34.693604023Z",
              "description": "<description>",
              "desire": null,
              "end_time": "2026-04-18T17:30:00Z",
              "group_id": null,
              "id": "d4f26212-07ac-4ebc-b5d9-fe4649a69a3e",
              "kind": "event",
              "labels": [],
              "org_slug": "u-e4h2qk",
              "owner_org_id": "ebca93e5-d663-4624-9fe9-c5361b5b4390",
              "parent_id": null,
              "pinned": false,
              "productive": null,
              "recurrence": { "days": ["Sat"], "type": "weekly" },
              "ref": "u-e4h2qk-176",
              "sequence": 176,
              "series_id": "1092979a-a04d-47be-b7e8-921ed3097005",
              "status": "active",
              "subtask_template": [],
              "title": "<title>",
              "type": "event"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesItemsSampleIntoTheCorrectSealedSubtypes() {
        val items = decodeData(ListSerializer(ItemView.serializer()), itemsSample)
        assertEquals(4, items.size)

        val task = assertIs<ItemView.Task>(items[0])
        assertEquals("948bcfab-063d-4499-b2de-f21801bc6f9c", task.id)
        assertEquals(TaskStatusWire.Open, task.status)

        val habit = assertIs<ItemView.Habit>(items[1])
        assertEquals("77dd6a6e-b936-4f61-9807-c3a6b647f9f1", habit.id)
        assertEquals(DefStatusWire.Active, habit.status)
        assertEquals("b7c21959-c5f6-4087-8ab2-7690c81e463a", habit.seriesId)

        val chore = assertIs<ItemView.Chore>(items[2])
        assertEquals("47338a14-a07f-4ddf-ad73-f5edc977dab0", chore.id)
        assertEquals("rolling", chore.cadenceMode)

        val event = assertIs<ItemView.Event>(items[3])
        assertEquals("d4f26212-07ac-4ebc-b5d9-fe4649a69a3e", event.id)
        assertEquals(false, event.allDay)
        assertEquals("2026-04-18T17:30:00Z", event.endTime)
    }
}

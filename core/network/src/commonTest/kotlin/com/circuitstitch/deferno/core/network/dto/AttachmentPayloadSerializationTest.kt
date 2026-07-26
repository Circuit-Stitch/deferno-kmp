package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The **write-body** contract for the attachment commit (#364). The presign *response* names an uploaded
 * file `attachment_id`; the commit *request* names it `id` (the contract's `IntentEntry`, whose only
 * required key that is). Carrying the response's key into the request parses to zero intents server-side —
 * a commit that succeeds while attaching nothing, with the bytes already stored. These pin the exact bytes
 * so that divergence fails here rather than on a user's device.
 */
class AttachmentPayloadSerializationTest {

    @Test
    fun theCommitIntentKeyIsIdNotAttachmentId() {
        val json = DefernoJson.encodeToString(CommitAttachmentsPayload(intents = listOf(AttachmentIntentDto("att-1"))))

        assertEquals("""{"intents":[{"id":"att-1"}]}""", json)
        assertFalse(json.contains("attachment_id"), "the presign response's key never appears on the commit")
        assertFalse(json.contains("caption"), "an uncaptioned intent omits the field rather than sending null")
    }

    @Test
    fun anUnstampedCommitIsByteIdenticalToThePreLedgerBody() {
        // encodeDefaults is off and explicitNulls = false on DefernoJson, so adding the optional `activity`
        // field changed nothing for the callers that don't set it — the point of putting it on the DTO.
        assertFalse(
            DefernoJson.encodeToString(CommitAttachmentsPayload(intents = listOf(AttachmentIntentDto("att-1"))))
                .contains("activity"),
        )
    }

    @Test
    fun aStampedCommitCarriesTheActivityObjectVerbatimBesideTheIntents() {
        // The stamp is opaque here on purpose (it is minted in core:data, which has no serialization
        // compiler plugin), so it must embed as a JSON object — not a stringified blob a server can't read.
        val stamp = buildJsonObject {
            put("id", "entry-1")
            put("at", "2026-04-17T10:00:00Z")
            put("source", "mobile")
        }
        val json = DefernoJson.encodeToString(
            CommitAttachmentsPayload(intents = listOf(AttachmentIntentDto("att-1")), activity = stamp),
        )

        assertEquals(
            """{"intents":[{"id":"att-1"}],"activity":{"id":"entry-1","at":"2026-04-17T10:00:00Z","source":"mobile"}}""",
            json,
        )
    }
}

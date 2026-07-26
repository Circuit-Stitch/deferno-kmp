package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The **attachment** view DTO (`GET /tasks/{id}/attachments`, and the `POST` commit's 201 body): one
 * file attached to a Task. Snake_case wire keys via [SerialName]; decoded by the tolerant
 * [com.circuitstitch.deferno.core.network.DefernoJson] (the `provider`/`caption_updated_*` fields the
 * wire also carries but the client ignores pass through).
 */
@Serializable
data class AttachmentViewDto(
    val id: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val url: String,
    val caption: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
)

/**
 * The **on-device (local) attachment** metadata nested per item in a Backup file's `items.json` (#315,
 * ADR-0041). It mirrors the shared keys of the API's [AttachmentViewDto] (`id`/`filename`/`mime`/`size`/
 * `caption`/`created_at`, all snake_case) so the file stays web-API-shaped, but drops the two fields a
 * *device-local* attachment genuinely lacks: `url` (its bytes live at `attachments/<id>` **inside the
 * zip**, not behind a signed URL) and `created_by` (there is no creator user id on the device). Carries
 * exactly what round-trips a `core:data` `LocalAttachment` through export→import. It **never appears on a
 * real API response** — there it defaults empty and the tolerant reader ignores it, so the item read path
 * is untouched.
 */
@Serializable
data class LocalAttachmentDto(
    val id: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val caption: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/**
 * The size-only projection of the `attachments` block nested per item on the `/items` snapshot and the
 * `/tasks/{id}` detail (#311). The full backend-hosted attachment object carries `id`/`filename`/`mime`/
 * `url`/… but the offline-search rollup only needs [size]; the tolerant reader
 * ([com.circuitstitch.deferno.core.network.DefernoJson], `ignoreUnknownKeys`) drops the rest, so this
 * stays a one-field shape. Defaulted to `0` so an entry missing the key decodes cleanly rather than
 * throwing (matching the reader's tolerant posture). Lets the client cache `attachment_count` +
 * `attachment_total_size` from data already on the wire — no backend rollup field needed (ADR-0042).
 */
@Serializable
data class AttachmentSizeDto(
    val size: Long = 0,
)

/**
 * `POST /tasks/{id}/attachments/presign` body — the batch of files to presign, identical in shape to
 * the feedback presign (reuses the generic [PresignRequestDto]). One [PresignResponseDto] comes back
 * per file, in order, under the response envelope's `data.attachments`.
 */
@Serializable
data class AttachmentPresignBatchRequestDto(
    val files: List<PresignRequestDto>,
)

/** The task-attachment presign response payload (the envelope `data`): one entry per requested file, in order. */
@Serializable
data class AttachmentPresignBatchResponseDto(
    val attachments: List<PresignResponseDto>,
)

/**
 * `POST /items/{id}/attachments` (commit) body — the presigned uploads to attach.
 *
 * Each entry is the contract's `IntentEntry`, whose one required key is **`id`** — deliberately NOT the
 * `attachment_id` the *presign response* hands back. The two names sit on opposite ends of the same
 * handshake, and a body echoing the response key parses to zero intents server-side: the upload is lost
 * after its bytes are already stored, with no error the user can see. A hand-rolled body made exactly
 * that mistake once (#364), which is why the shape lives here — one place to get it right.
 *
 * [activity] is the client-minted Activity-ledger stamp (#364), kept as a raw [JsonObject] rather than a
 * typed `ActivityMeta`: it is minted in `core:data`, which carries the serialization runtime but not the
 * compiler plugin. Hanging the untyped sibling off the typed payload is what keeps the body under a
 * single serializer instead of forcing the whole thing to be hand-built. An unstamped commit is
 * byte-identical to the pre-ledger one — the shared [com.circuitstitch.deferno.core.network.DefernoJson]
 * leaves defaults unencoded (`encodeDefaults` off) and drops nulls (`explicitNulls = false`).
 *
 * The wire also accepts `urls` (link attachments); the client uploads files only, so that field is omitted.
 */
@Serializable
data class CommitAttachmentsPayload(
    val intents: List<AttachmentIntentDto>,
    val activity: JsonObject? = null,
)

/** One commit intent: a previously-presigned [id], with an optional [caption] (unused in v1). */
@Serializable
data class AttachmentIntentDto(
    val id: String,
    val caption: String? = null,
)

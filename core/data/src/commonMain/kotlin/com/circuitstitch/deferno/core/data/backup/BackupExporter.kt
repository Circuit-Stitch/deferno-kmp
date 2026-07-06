package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.attachment.LocalAttachmentRepository
import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.SupportedApiVersions
import com.circuitstitch.deferno.core.network.dto.ItemView
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The on-device export engine (#313, ADR-0041): reads the person's items from the local source-of-truth
 * DB and writes a [Backup file][buildBackupZip] — a zip whose single `items.json` entry **is** the
 * REST response envelope `{ version, data }`, carrying the same snake-case `core:network` DTO shapes the
 * API's read endpoints emit. "Compatible with the web API" holds by construction: `items.json` is the
 * API's own JSON, serialized by the very same [DefernoJson], and it carries the envelope [version].
 *
 * Shared KMP core so Android/desktop inherit it; iOS contributes only the share-sheet bridge.
 *
 * **Honest partial snapshot (ADR-0041).** Items only (Task/Habit/Chore/Event) plus **on-device attachment
 * bytes** (#315): kept brain-dump recordings, embedded raw at `attachments/<id>` with their metadata nested
 * under the owning Task. Backend-hosted attachments are still referenced by their size-only rollup only —
 * **never fetched** (the device holds no bytes/full metadata for them; that is the deferred Full extract's
 * job). Comments/history aren't cached locally, so they are omitted. Tombstones are excluded (the stores'
 * `observeActive`), `external`-provenance Tasks are excluded (the integration re-creates them on sync),
 * and an un-hydrated item exports without its `description` because the device simply doesn't hold it.
 *
 * [localAttachments] is optional: absent → an items-only snapshot (the pre-#315 behaviour, and the pure
 * `commonTest` fast path); present (prod, per-Account) → on-device attachment bytes are embedded too.
 */
class BackupExporter(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val localAttachments: LocalAttachmentRepository? = null,
    private val json: Json = DefernoJson,
) {
    /** The `items.json` body: the `{ version, data }` envelope of cross-kind item DTOs. */
    suspend fun buildItemsJson(): String = encodeEnvelope(collect().items)

    /**
     * The Backup file: a zip whose [ITEMS_ENTRY] is the `{ version, data }` envelope and whose
     * `attachments/<id>` entries hold each embedded on-device attachment's raw bytes (#315).
     */
    suspend fun buildBackupZip(): ByteArray {
        val collected = collect()
        val entries = buildList {
            add(ITEMS_ENTRY to encodeEnvelope(collected.items).encodeToByteArray())
            collected.attachmentBlobs.forEach { (id, bytes) -> add("$ATTACHMENTS_DIR/$id" to bytes) }
        }
        return zipStored(entries)
    }

    private fun encodeEnvelope(items: List<ItemView>): String {
        val envelope = Envelope(version = SupportedApiVersions.MAX.toString(), data = items)
        return json.encodeToString(Envelope.serializer(ListSerializer(ItemView.serializer())), envelope)
    }

    /**
     * Reads the four per-kind stores into DTOs and, for each Task, embeds its on-device attachments: the
     * bytes are collected for the zip and the metadata nested under the Task. An attachment whose bytes have
     * gone missing is silently skipped — the file only claims what it actually carries.
     */
    private suspend fun collect(): Collected {
        val blobs = mutableListOf<Pair<String, ByteArray>>()
        val tasks = taskStore.observeActive().first()
            .filter { it.external == null } // external items are re-created on sync — never exported
            .map { task ->
                val view = task.toItemView()
                val local = localAttachments?.forTask(task.id.value).orEmpty()
                if (local.isEmpty()) return@map view
                val dtos = local.mapNotNull { att ->
                    val bytes = localAttachments?.bytes(att.id) ?: return@mapNotNull null // bytes gone → skip
                    blobs += att.id to bytes
                    att.toDto()
                }
                view.copy(localAttachments = dtos)
            }
        val habits = habitStore.observeActive().first().map { it.toItemView() }
        val chores = choreStore.observeActive().first().map { it.toItemView() }
        val events = eventStore.observeActive().first().map { it.toItemView() }
        return Collected(tasks + habits + chores + events, blobs)
    }

    private class Collected(val items: List<ItemView>, val attachmentBlobs: List<Pair<String, ByteArray>>)

    companion object {
        /** The items envelope's path inside the Backup file zip. */
        const val ITEMS_ENTRY: String = "items.json"

        /** The zip directory holding each on-device attachment's raw bytes, keyed by attachment id (#315). */
        const val ATTACHMENTS_DIR: String = "attachments"
    }
}

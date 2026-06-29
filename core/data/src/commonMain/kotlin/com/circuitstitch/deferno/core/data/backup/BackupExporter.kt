package com.circuitstitch.deferno.core.data.backup

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
 * DB and writes a [Backup file][buildBackupZip] — a zip whose single `manifest.json` entry **is** the
 * REST response envelope `{ version, data }`, carrying the same snake-case `core:network` DTO shapes the
 * API's read endpoints emit. "Compatible with the web API" holds by construction: the manifest is the
 * API's own JSON, serialized by the very same [DefernoJson], and it carries the envelope [version].
 *
 * Shared KMP core so Android/desktop inherit it; iOS contributes only the share-sheet bridge.
 *
 * **Honest partial snapshot (ADR-0041).** Items only (Task/Habit/Chore/Event), offline, local DB only —
 * no attachments/comments/history (not cached locally). Tombstones are excluded (the stores'
 * `observeActive`), `external`-provenance Tasks are excluded (the integration re-creates them on sync),
 * and an un-hydrated item exports without its `description` because the device simply doesn't hold it.
 */
class BackupExporter(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val json: Json = DefernoJson,
) {
    /** The `manifest.json` body: the `{ version, data }` envelope of cross-kind item DTOs. */
    suspend fun buildManifestJson(): String {
        val envelope = Envelope(version = SupportedApiVersions.MAX.toString(), data = collectItems())
        return json.encodeToString(Envelope.serializer(ListSerializer(ItemView.serializer())), envelope)
    }

    /** The Backup file: a zip whose only entry is [MANIFEST_ENTRY] (attachments join in a later slice). */
    suspend fun buildBackupZip(): ByteArray =
        zipStored(listOf(MANIFEST_ENTRY to buildManifestJson().encodeToByteArray()))

    private suspend fun collectItems(): List<ItemView> {
        val tasks = taskStore.observeActive().first()
            .filter { it.external == null } // external items are re-created on sync — never exported
            .map { it.toItemView() }
        val habits = habitStore.observeActive().first().map { it.toItemView() }
        val chores = choreStore.observeActive().first().map { it.toItemView() }
        val events = eventStore.observeActive().first().map { it.toItemView() }
        return tasks + habits + chores + events
    }

    companion object {
        /** The manifest's path inside the Backup file zip. */
        const val MANIFEST_ENTRY: String = "manifest.json"
    }
}

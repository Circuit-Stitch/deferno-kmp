package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.create.PendingCreateStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.CreateChoreItem
import com.circuitstitch.deferno.core.data.outbox.CreateEventItem
import com.circuitstitch.deferno.core.data.outbox.CreateHabitItem
import com.circuitstitch.deferno.core.data.outbox.CreateMutation
import com.circuitstitch.deferno.core.data.outbox.CreateTaskItem
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.task.TaskLocalStore
import com.circuitstitch.deferno.core.network.ApiVersion
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.Envelope
import com.circuitstitch.deferno.core.network.SupportedApiVersions
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
import com.circuitstitch.deferno.core.network.mapper.asEventOrNull
import com.circuitstitch.deferno.core.network.mapper.asHabitOrNull
import com.circuitstitch.deferno.core.network.mapper.asTaskOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The on-device import/restore engine (#314, ADR-0041): the inverse of [BackupExporter]. It parses a
 * [Backup file][import] (a zip whose `manifest.json` **is** the REST `{ version, data }` envelope),
 * version-gates it against ADR-0005's window, and replays each item as an **id-preserving create** on the
 * existing offline outbox — an optimistic local upsert (full read-side fidelity) plus a `POST /{kind}`
 * carrying the item's **original id**. Because the backend honors and dedupes on that id (ADR-0034) the
 * restore is **idempotent** (re-importing changes nothing) and **offline-first** (it just enqueues;
 * replay happens when online). Items land in the active account's personal org — the create payloads
 * carry no org, so the server re-homes regardless of the file's `owner_org_id`.
 *
 * Shared KMP core so Android/desktop inherit it; iOS contributes only the document picker. Mirrors
 * [BackupExporter]'s constructor (the four per-kind stores) plus the outbox + pending-create side table
 * the create path writes — the same two lines [com.circuitstitch.deferno.core.data.create.OfflineCreateWriter]
 * uses, kept local here rather than coupling the importer to that writer's id-minting create methods.
 */
class BackupImporter(
    private val taskStore: TaskLocalStore,
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val outbox: OutboxStore,
    private val pendingCreateStore: PendingCreateStore,
    private val json: Json = DefernoJson,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /**
     * Restore a [Backup file] zip's items. Reports the outcome rather than throwing: [ImportResult.Malformed]
     * for a non-zip / missing-or-unparseable manifest, [ImportResult.ForceUpgrade] / [ImportResult.Unsupported]
     * for a version outside ADR-0005's window, or [ImportResult.Restored] with the count enqueued.
     *
     * **No partial corruption.** Parse + version-gate + map *all* items to their domain rows + create
     * intents — all pure, no writes — before the first store/outbox write; any failure short-circuits to
     * [ImportResult.Malformed] with nothing written.
     */
    suspend fun import(bytes: ByteArray): ImportResult {
        val manifest = runCatching { unzipStored(bytes)[BackupExporter.MANIFEST_ENTRY] }.getOrNull()
            ?: return ImportResult.Malformed
        val envelope = runCatching {
            json.decodeFromString(Envelope.serializer(ListSerializer(ItemView.serializer())), manifest.decodeToString())
        }.getOrNull() ?: return ImportResult.Malformed

        versionGate(envelope.version)?.let { return it }

        // Pure pass: map every item to its (upsert, create) — a throw (e.g. a bad timestamp) means malformed
        // and writes nothing. Only after every item maps do we touch the stores/outbox.
        val ops = runCatching { envelope.data.map(::restoreOp) }.getOrNull() ?: return ImportResult.Malformed
        ops.forEach { it() }
        return ImportResult.Restored(ops.size)
    }

    /**
     * Builds the per-item restore: the read-side mapping (`asXOrNull()`, the one step that can throw on a
     * corrupt timestamp) runs *now* during the pure pass, while the returned [suspend] closure defers the
     * store/outbox writes to the apply pass. The `!!` is sound — the `when` already matched the kind.
     */
    private fun restoreOp(item: ItemView): suspend () -> Unit = when (item) {
        is ItemView.Task -> {
            val row = item.asTaskOrNull()!!
            val mutation = CreateTaskItem(item.id, item.toCreatePayload())
            suspend { taskStore.upsert(row); enqueueCreate(mutation) }
        }
        is ItemView.Habit -> {
            val row = item.asHabitOrNull()!!
            val mutation = CreateHabitItem(item.id, item.toCreatePayload())
            suspend { habitStore.upsert(row); enqueueCreate(mutation) }
        }
        is ItemView.Chore -> {
            val row = item.asChoreOrNull()!!
            val mutation = CreateChoreItem(item.id, item.toCreatePayload())
            suspend { choreStore.upsert(row); enqueueCreate(mutation) }
        }
        is ItemView.Event -> {
            val row = item.asEventOrNull()!!
            val mutation = CreateEventItem(item.id, item.toCreatePayload())
            suspend { eventStore.upsert(row); enqueueCreate(mutation) }
        }
    }

    /** Records the pending create + enqueues its replayable id-preserving outbox entry (ADR-0001/0034). */
    private suspend fun enqueueCreate(mutation: CreateMutation) {
        pendingCreateStore.add(mutation.itemId, mutation.itemKind)
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }

    /** ADR-0005 window: above MAX → force-upgrade, below MIN → refuse, unparseable → malformed, else proceed. */
    private fun versionGate(raw: String): ImportResult? {
        val version = ApiVersion.parseOrNull(raw) ?: return ImportResult.Malformed
        return when {
            version > SupportedApiVersions.MAX -> ImportResult.ForceUpgrade
            version < SupportedApiVersions.MIN -> ImportResult.Unsupported
            else -> null
        }
    }
}

/** The outcome of [BackupImporter.import] — the host maps each to its user-facing message (iOS document picker). */
sealed interface ImportResult {
    /** [count] items were enqueued as id-preserving creates (they sync when online). */
    data class Restored(val count: Int) : ImportResult

    /** Manifest `version` is above [SupportedApiVersions.MAX] — "update Deferno to import this backup." */
    data object ForceUpgrade : ImportResult

    /** Manifest `version` is below [SupportedApiVersions.MIN] — too old to import; refused. */
    data object Unsupported : ImportResult

    /** Not a readable Backup file: not a zip, no `manifest.json`, or an unparseable/invalid manifest. */
    data object Malformed : ImportResult
}

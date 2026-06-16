package com.circuitstitch.deferno.core.data.item

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The device-local **fold state** for the Item tree (ADR-0034 decision 4, #227): the explicit
 * expand/collapse **overrides** a user has set, keyed by item id, against the depth-based default
 * (auto-collapse anything deeper than depth 2). An **[[App setting]]** — stored device-locally, **never
 * synced** (fold memory is a per-device view convenience; the backend `UserSettings` is untouched).
 *
 * **One live store, every tree surface.** Both the Tasks tree ([com.circuitstitch.deferno.feature.tasks]
 * `ItemTreeComponent`) and the detail subtask outline (`TaskDetailComponent`) observe this single
 * [overrides] flow and persist through [setOverride], so a node folded on one surface re-flattens the
 * other **live** (both observe the same flow) and the choice survives restart. Only *explicit* overrides
 * are stored; an item with no entry falls back to the depth default (so the map stays small — it grows
 * only as the user toggles nodes).
 *
 * Mirrors the device-local preference pattern of
 * [com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference].
 */
interface ItemFoldStore {
    /**
     * The live explicit overrides, keyed by item id (`true` = expanded, `false` = collapsed). Every tree
     * surface combines this flow into its state, so a [setOverride] on one re-flattens all of them at once.
     */
    val overrides: StateFlow<Map<String, Boolean>>

    /** Persist an explicit expand/collapse override for [itemId]. Device-local, never synced. */
    fun setOverride(itemId: String, expanded: Boolean)
}

/**
 * A non-persistent [ItemFoldStore] for tests, previews, and the Apple targets whose native tree is a
 * deferred fast-follow (ADR-0034). **Measured** (commonTest) — round-trips overrides in memory.
 */
class InMemoryItemFoldStore(initial: Map<String, Boolean> = emptyMap()) : ItemFoldStore {
    private val _overrides = MutableStateFlow(initial)
    override val overrides: StateFlow<Map<String, Boolean>> = _overrides
    override fun setOverride(itemId: String, expanded: Boolean) {
        _overrides.update { it + (itemId to expanded) }
    }
}

/**
 * The production [ItemFoldStore] over a multiplatform-settings [Settings] (#227): one commonMain impl over
 * the platform-backed store (Android SharedPreferences / desktop Preferences), each override a namespaced
 * boolean key. The live [overrides] flow is seeded once from the persisted keys, then kept in lockstep with
 * each [setOverride] write. Excluded from the coverage gate (a thin store adapter exercised through the
 * platform store, not the headless JVM gate, ADR-0006) — like `SettingsKeepBrainDumpRecordingsPreference`.
 */
class SettingsItemFoldStore(private val settings: Settings) : ItemFoldStore {
    private val _overrides = MutableStateFlow(readPersisted())
    override val overrides: StateFlow<Map<String, Boolean>> = _overrides

    override fun setOverride(itemId: String, expanded: Boolean) {
        settings.putBoolean(KEY_PREFIX + itemId, expanded)
        _overrides.update { it + (itemId to expanded) }
    }

    private fun readPersisted(): Map<String, Boolean> =
        settings.keys.filter { it.startsWith(KEY_PREFIX) }
            .associate { it.removePrefix(KEY_PREFIX) to settings.getBoolean(it, false) }

    private companion object {
        const val KEY_PREFIX = "item.fold."
    }
}

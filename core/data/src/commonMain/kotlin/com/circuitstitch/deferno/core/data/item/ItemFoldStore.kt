package com.circuitstitch.deferno.core.data.item

import com.russhwolf.settings.Settings

/**
 * The device-local **fold state** for the Item tree (ADR-0034 decision 4, #227): the explicit
 * expand/collapse **overrides** a user has set, keyed by item id, against the depth-based default
 * (auto-collapse anything deeper than depth 2). An **[[App setting]]** — stored device-locally, **never
 * synced** (fold memory is a per-device view convenience; the backend `UserSettings` is untouched).
 *
 * **One store, every tree surface.** Both the Tasks tree and the detail subtask outline consult this same
 * store, so a node folded on one surface stays folded on the other and across restart. Only *explicit*
 * overrides are stored; an item with no entry falls back to the depth default (so the map stays small —
 * it grows only as the user toggles nodes).
 *
 * Mirrors the device-local preference pattern of
 * [com.circuitstitch.deferno.core.data.braindump.KeepBrainDumpRecordingsPreference].
 */
interface ItemFoldStore {
    /**
     * Every persisted explicit override, keyed by item id (`true` = expanded, `false` = collapsed). A
     * tree surface seeds its in-memory fold state from this once, then writes through [setOverride].
     */
    fun allOverrides(): Map<String, Boolean>

    /** Persist an explicit expand/collapse override for [itemId]. Device-local, never synced. */
    fun setOverride(itemId: String, expanded: Boolean)
}

/**
 * A non-persistent [ItemFoldStore] for tests, previews, and the Apple targets whose native tree is a
 * deferred fast-follow (ADR-0034). **Measured** (commonTest) — round-trips overrides in memory.
 */
class InMemoryItemFoldStore(initial: Map<String, Boolean> = emptyMap()) : ItemFoldStore {
    private val overrides = initial.toMutableMap()
    override fun allOverrides(): Map<String, Boolean> = overrides.toMap()
    override fun setOverride(itemId: String, expanded: Boolean) {
        overrides[itemId] = expanded
    }
}

/**
 * The production [ItemFoldStore] over a multiplatform-settings [Settings] (#227): one commonMain impl over
 * the platform-backed store (Android SharedPreferences / desktop Preferences), each override a namespaced
 * boolean key. Excluded from the coverage gate (a thin store adapter exercised through the platform store,
 * not the headless JVM gate, ADR-0006) — like `SettingsKeepBrainDumpRecordingsPreference`.
 */
class SettingsItemFoldStore(private val settings: Settings) : ItemFoldStore {
    override fun allOverrides(): Map<String, Boolean> =
        settings.keys.filter { it.startsWith(KEY_PREFIX) }
            .associate { it.removePrefix(KEY_PREFIX) to settings.getBoolean(it, false) }

    override fun setOverride(itemId: String, expanded: Boolean) {
        settings.putBoolean(KEY_PREFIX + itemId, expanded)
    }

    private companion object {
        const val KEY_PREFIX = "item.fold."
    }
}

package com.circuitstitch.deferno.core.data.item

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SettingsItemFoldStore] against a real `NSUserDefaults` suite — the binding that replaced the in-memory
 * placeholder on **both** Apple targets (ADR-0049, #227), so a fold the user sets on the Tasks tree or the
 * detail subtask outline survives relaunch. In `appleTest` rather than `iosTest` deliberately: iOS and macOS
 * bind the identical store over the identical API, so one test runs on both (`iosSimulatorArm64Test` +
 * `macosArm64Test`) and neither can regress alone.
 *
 * The store itself is commonMain and already round-tripped in `commonTest`; what only an Apple test can show
 * is the pair of properties the `NSUserDefaults` backing actually turns on:
 *
 *  1. **a fresh instance reads what a previous one wrote** — the cold-start property the placeholder could
 *     never have (every relaunch re-collapsed the whole forest), which is the whole point of the swap;
 *  2. **the seed picks up only its own namespaced keys.** This is not paranoia about our own writes: a
 *     suite's `dictionaryRepresentation()` returns the union of the *whole search list* — the suite domain,
 *     the app domain, and Apple's global domain (`AppleLanguages`, `AppleLocale`, …) — and that dictionary
 *     is exactly what `Settings.keys` exposes. Without the `item.fold.` prefix filter the seed would try to
 *     read Apple's own keys (and the neighbouring App settings sharing the `deferno_storage` bag) as fold
 *     booleans. The unrelated-neighbour case below is the assertable half of that.
 */
class SettingsItemFoldStoreAppleTest {

    // A per-run suite so the test never reads or clobbers the app's real `deferno_storage` bag.
    private val suite = "deferno-fold-test-${Random.nextLong()}"

    private fun store() = SettingsItemFoldStore(NSUserDefaultsSettings.Factory().create(suite))

    @AfterTest
    fun removeSuite() {
        // Drop the whole domain rather than `Settings.clear()` — that clears every key `keys` reports,
        // which (see above) would include the global domain.
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    @Test
    fun freshInstanceReadsWhatAPreviousOneWrote() {
        val first = store()
        first.setOverride("item-a", expanded = true)
        first.setOverride("item-b", expanded = false)

        // A new instance over the same suite = the next app launch.
        val second = store()
        assertEquals(mapOf("item-a" to true, "item-b" to false), second.overrides.value)
    }

    @Test
    fun lastWriteWinsAcrossLaunches() {
        store().setOverride("item-a", expanded = true)
        store().setOverride("item-a", expanded = false)

        assertEquals(mapOf("item-a" to false), store().overrides.value)
    }

    @Test
    fun seedsOnlyItsOwnNamespacedKeys() {
        val settings = NSUserDefaultsSettings.Factory().create(suite)
        // A neighbouring App setting sharing the same bag, as `deferno_storage` really does on iOS.
        settings.putBoolean("brain_dump.keep_recordings", true)
        SettingsItemFoldStore(settings).setOverride("item-a", expanded = true)

        // Exact-match, not `containsKey`: the neighbour AND every global-domain key must stay out.
        assertEquals(mapOf("item-a" to true), store().overrides.value)
    }
}

package com.circuitstitch.deferno.l10n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the two localization catalogs in lockstep so a user-facing string can't silently drift onto
 * one platform only:
 *  - Compose (the source of truth): core/designsystem/.../composeResources/values/strings.xml
 *  - Apple SwiftUI:                 app/shared-l10n/Localizable.xcstrings (symlinked into iosApp + macosApp)
 *
 * A key in one catalog but not the other fails the test unless it is listed in
 * `app/shared-l10n/l10n-parity-overrides.txt` as a deliberate platform-only string. Stale override
 * entries (the divergence they cover is gone) also fail, so the grandfathered baseline shrinks as gaps
 * get reconciled instead of rotting.
 *
 * Pure file IO + set math (no Android/Robolectric) so it runs on the JVM-fast `check` path CI already
 * invokes. Reads the xcstrings with a real JSON parser, not indentation-regex, so reformatting the
 * catalog can't fool it.
 */
class L10nCatalogParityTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")

    /** Keys in the English Compose catalog (`<string>` / `<plurals>` names) — the source of truth. */
    private val composeKeys: Set<String> =
        repoRoot.resolve("core/designsystem/src/commonMain/composeResources/values/strings.xml")
            .readText()
            .let { xml ->
                Regex("""<(?:string|plurals)\s+name="([^"]+)"""")
                    .findAll(xml).map { it.groupValues[1] }.toSet()
            }

    /** Top-level keys in the Apple xcstrings catalog. */
    private val appleKeys: Set<String> =
        Json.parseToJsonElement(repoRoot.resolve("app/shared-l10n/Localizable.xcstrings").readText())
            .jsonObject["strings"]!!.jsonObject.keys.toSet()

    private val androidOnly = mutableSetOf<String>()
    private val appleOnly = mutableSetOf<String>()

    init {
        repoRoot.resolve("app/shared-l10n/l10n-parity-overrides.txt").readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("""\s+"""), limit = 2)
            require(parts.size == 2) { "malformed override line: '$raw'" }
            when (parts[0]) {
                "android" -> androidOnly += parts[1]
                "apple" -> appleOnly += parts[1]
                else -> error("unknown platform '${parts[0]}' in override line: '$raw'")
            }
        }
    }

    @Test
    fun everyAppleStringHasAComposeSource() {
        val missing = (appleKeys - composeKeys - appleOnly).sorted()
        assertTrue(
            "iOS/macOS strings with no Compose source of truth — add each to " +
                "core/designsystem/.../values*/strings.xml (all 5 locales), or mark 'apple <key>' in " +
                "app/shared-l10n/l10n-parity-overrides.txt:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun everyComposeStringReachesApple() {
        val missing = (composeKeys - appleKeys - androidOnly).sorted()
        assertTrue(
            "Compose strings with no iOS/macOS counterpart — add each to " +
                "app/shared-l10n/Localizable.xcstrings, or mark 'android <key>' in " +
                "app/shared-l10n/l10n-parity-overrides.txt:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun noStaleOverrides() {
        val stale =
            androidOnly.filter { it !in composeKeys || it in appleKeys }.sorted().map { "android $it" } +
                appleOnly.filter { it !in appleKeys || it in composeKeys }.sorted().map { "apple $it" }
        assertTrue(
            "Stale l10n-parity-overrides.txt entries — the divergence they cover is gone (key now in " +
                "both catalogs, or removed). Delete these lines:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }
}

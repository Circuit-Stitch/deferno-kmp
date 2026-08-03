package com.circuitstitch.deferno.l10n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The structural half of the l10n gate, complementing [L10nCatalogParityTest].
 *
 * `L10nCatalogParityTest` compares key *sets* — it never looks inside an entry, and it reads only
 * `app/shared-l10n/Localizable.xcstrings`. Three real failure modes slip through it, all of which ship
 * a raw catalog key onto an Apple screen while `check` stays green:
 *
 *  1. **The symlink gets clobbered.** `SWIFT_EMIT_LOC_STRINGS = YES` (`app/iosApp/iosApp.xcodeproj/
 *     project.pbxproj`) makes Xcode rewrite `app/iosApp/iosApp/Localizable.xcstrings` *in place*,
 *     replacing the symlink with a ~560KB regular file. From then on the iOS app is built against a
 *     frozen private copy while every new key lands in the shared catalog nobody reads.
 *  2. **A half-added key.** An entry with four of the five locales, or an empty value, satisfies the
 *     key-set math; the missing locale silently falls back to the development language at runtime.
 *  3. **An untranslated stub.** Xcode writes `"state": "new"` / `"needs_review"` for a key it extracted
 *     but nobody translated.
 *
 * Plus a value check on the one vocabulary this change *promoted* out of the Compose-only override list
 * (#393): a promoted string is a verbatim copy, never a re-translation. That rule is deliberately NOT
 * asserted catalog-wide — 88 legacy entries already word themselves differently on Apple, and
 * reconciling those is not this test's job.
 *
 * Pure file IO + JSON parsing, no Robolectric, so it runs on the JVM-fast `check` path alongside its
 * sibling.
 */
class AppleCatalogIntegrityTest {

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")

    private val sharedCatalog = repoRoot.resolve("app/shared-l10n/Localizable.xcstrings")

    /** The five locales the project ships; every entry must carry exactly these. */
    private val locales = setOf("en", "es", "de", "hi", "pt")

    private val strings: Map<String, JsonObject> =
        Json.parseToJsonElement(sharedCatalog.readText())
            .jsonObject["strings"]!!
            .jsonObject
            .mapValues { it.value.jsonObject }

    /** `key -> locale -> localization object` (either a `stringUnit` or plural `variations`). */
    private fun localizationsOf(entry: JsonObject): Map<String, JsonObject> =
        entry["localizations"]?.jsonObject?.mapValues { it.value.jsonObject } ?: emptyMap()

    @Test
    fun appleCatalogsAreSymlinksIntoTheSharedCatalog() {
        val shared = sharedCatalog.toPath().toRealPath()
        val broken = listOf(
            "app/iosApp/iosApp/Localizable.xcstrings",
            "app/macosApp/macosApp/Localizable.xcstrings",
        ).mapNotNull { relative ->
            val path = repoRoot.resolve(relative).toPath()
            when {
                !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> "$relative is missing"
                !Files.isSymbolicLink(path) ->
                    "$relative is a regular file, not a symlink — Xcode (SWIFT_EMIT_LOC_STRINGS = YES) " +
                        "clobbered it; restore with `git checkout -- $relative`"
                path.toRealPath() != shared -> "$relative resolves to ${path.toRealPath()}, not $shared"
                else -> null
            }
        }
        assertTrue(
            "The Apple apps must build against the ONE shared catalog (app/shared-l10n/" +
                "Localizable.xcstrings). A detached copy renders raw keys on screen while " +
                "L10nCatalogParityTest — which reads only the shared file — stays green:\n" +
                broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }

    @Test
    fun everyAppleEntryCarriesAllFiveLocales() {
        val drift = strings.entries.flatMap { (key, entry) ->
            val have = localizationsOf(entry).keys
            (locales - have).sorted().map { "$key is missing $it" } +
                (have - locales).sorted().map { "$key has unexpected locale $it" }
        }.sorted()
        assertTrue(
            "Localizable.xcstrings entries out of lockstep with the 5 shipped locales — a hole falls " +
                "back to the development language at runtime, invisibly:\n" + drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    @Test
    fun everyAppleStringUnitIsTranslatedAndNonBlank() {
        val bad = strings.entries.flatMap { (key, entry) ->
            localizationsOf(entry).flatMap { (locale, localization) ->
                val units = localization["stringUnit"]?.let { listOf("" to it.jsonObject) }
                    ?: localization["variations"]?.jsonObject?.get("plural")?.jsonObject
                        ?.map { (category, unit) -> category to unit.jsonObject["stringUnit"]!!.jsonObject }
                    ?: return@flatMap listOf("$key/$locale has neither stringUnit nor plural variations")
                units.mapNotNull { (category, unit) ->
                    val where = if (category.isEmpty()) "$key/$locale" else "$key/$locale[$category]"
                    val state = unit["state"]?.jsonPrimitive?.content
                    val value = unit["value"]?.jsonPrimitive?.content
                    when {
                        state != "translated" -> "$where is '$state', not 'translated'"
                        value.isNullOrBlank() -> "$where has a blank value"
                        else -> null
                    }
                }
            }
        }.sorted()
        assertTrue(
            "Untranslated or blank Localizable.xcstrings units — the key-set parity check can't see " +
                "these, so they reach the screen as a stub:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun everyApplePluralCarriesOneAndOtherInEveryLocale() {
        val bad = strings.entries.flatMap { (key, entry) ->
            val localizations = localizationsOf(entry)
            val pluralLocales = localizations.filterValues { it.containsKey("variations") }.keys
            when {
                pluralLocales.isEmpty() -> emptyList()
                pluralLocales != localizations.keys ->
                    listOf("$key is a plural in ${pluralLocales.sorted()} but a plain string elsewhere")
                else -> localizations.mapNotNull { (locale, localization) ->
                    val categories = localization["variations"]!!.jsonObject["plural"]!!.jsonObject.keys
                    // en/es/de/hi/pt are all CLDR one+other; a `few`/`many` bucket here would be a
                    // locale the Compose `<plurals>` twin cannot express.
                    if (categories == setOf("one", "other")) null
                    else "$key/$locale has categories ${categories.sorted()}, expected [one, other]"
                }
            }
        }.sorted()
        assertTrue(
            "Localizable.xcstrings plural entries must be one+other in all 5 locales, matching their " +
                "Compose <plurals> twin:\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun kindVocabularyIsCopiedVerbatimFromCompose() {
        val composeByLocale = mapOf(
            "en" to composeStringsOf("values"),
            "es" to composeStringsOf("values-es"),
            "de" to composeStringsOf("values-de"),
            "hi" to composeStringsOf("values-hi"),
            "pt" to composeStringsOf("values-pt"),
        )
        val kindKeys = composeByLocale.getValue("en").keys
            .filter { it.matches(Regex("""tasks_kind_(a11y|label)_\w+""")) }
            .sorted()
        // 4 kinds x {spoken word, visible chip}. Guards against the filter silently matching nothing.
        assertEquals("expected the 8 promoted kind keys, got $kindKeys", 8, kindKeys.size)

        val drift = kindKeys.flatMap { key ->
            val entry = strings[key] ?: return@flatMap listOf("$key is absent from Localizable.xcstrings")
            localizationsOf(entry).mapNotNull { (locale, localization) ->
                val apple = localization["stringUnit"]!!.jsonObject["value"]!!.jsonPrimitive.content
                val compose = composeByLocale.getValue(locale)[key]
                if (apple == compose) null else "$key/$locale: Apple '$apple' != Compose '$compose'"
            }
        }.sorted()
        assertTrue(
            "The kind vocabulary was PROMOTED out of l10n-parity-overrides.txt (#393), so each Apple " +
                "value is a verbatim copy of its Compose source — never a second translation. Note the " +
                "split the values encode: lowercase tasks_kind_a11y_* is what VoiceOver reads, all-caps " +
                "tasks_kind_label_* is the visible chip.\n" + drift.joinToString("\n"),
            drift.isEmpty(),
        )
    }

    /** `<string name="k">v</string>` pairs from one Compose locale dir. `<plurals>` are skipped. */
    private fun composeStringsOf(valuesDir: String): Map<String, String> =
        repoRoot.resolve("core/designsystem/src/commonMain/composeResources/$valuesDir/strings.xml")
            .readText()
            .let { xml ->
                Regex("""<string\s+name="([^"]+)"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml)
                    .associate { it.groupValues[1] to unescapeXml(it.groupValues[2]) }
            }

    /** The five predefined XML entities; the catalogs use no others (no DTD, no numeric refs). */
    private fun unescapeXml(raw: String): String =
        raw.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
}

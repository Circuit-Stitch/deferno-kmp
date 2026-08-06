package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Instant

/**
 * The client half of the `tools/migration-probe` harness: does the grid **staging actually serves**
 * still agree with the grid **this client expands offline**, across a backend deploy?
 *
 * [RecurrenceCorpusTest] cannot answer that. It pins [expandOccurrenceGrid] to a *snapshot* of the
 * Rust, committed under `contracts/recurrence-corpus/`. A backend change that moves the grid and
 * regenerates nothing leaves that test green while every client on the network quietly disagrees with
 * the server — which is precisely the failure mode a migration + expansion-semantics PR can produce.
 * This test closes the loop by replaying the probe's **live** captures: for each seeded definition the
 * probe records the server's own `series` block, its `recurrence`, and the instants the server's
 * calendar feed actually returned.
 *
 * **What it fails on, and why only that.** A case that disagreed *before* the deploy is not this
 * test's business: the server bounds a live grid by things a pure expander deliberately does not model
 * (the `complete_by` cursor, feed windowing, per-kind caps), so a standing mismatch is a property of
 * the read surface, not a regression. The regression signal is a case that **agreed pre and disagrees
 * post** — the deploy moved the server away from the client. Standing mismatches are reported as an
 * inventory so they stay visible without turning into noise.
 *
 * **Opt-in, and silent otherwise.** With no `DEFERNO_PROBE_DIR` (or no captures in it) this passes
 * without asserting — it is a harness step, not a CI gate, and it must never fail a `check` on a
 * machine that never ran the probe. Gradle caches by inputs, so re-run it with `--rerun-tasks`:
 *
 * ```sh
 * DEFERNO_PROBE_DIR=tools/migration-probe/runs/m16-habit-local-date \
 *   ./gradlew :core:model:jvmTest --tests '*StagingGridParityTest*' --rerun-tasks
 * ```
 */
class StagingGridParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theDeployDidNotMoveTheServerGridAwayFromTheOfflineExpander() {
        val dir = probeDir() ?: return skip("DEFERNO_PROBE_DIR is not set")
        val pre = casesIn(dir, "pre") ?: return skip("no grid-cases-pre.json in $dir — run `probe.py capture pre`")
        val post = casesIn(dir, "post") ?: return skip("no grid-cases-post.json in $dir — the deploy has not happened yet")

        val regressions = mutableListOf<String>()
        val standing = mutableListOf<String>()
        val healed = mutableListOf<String>()

        for ((name, postCase) in post) {
            val preCase = pre[name] ?: continue
            val before = compare(preCase)
            val after = compare(postCase)
            when {
                before.agrees && !after.agrees -> regressions += render(name, "REGRESSED", postCase, after)
                !before.agrees && after.agrees -> healed += "$name (disagreed pre, agrees post)"
                !after.agrees -> standing += "$name: ${after.summary}"
            }
        }

        val onlyPost = post.keys - pre.keys
        val onlyPre = pre.keys - post.keys

        println(banner(pre.size, post.size, regressions, standing, healed, onlyPre, onlyPost))

        if (regressions.isNotEmpty()) {
            fail(
                "${regressions.size} definition(s) that the offline expander agreed with BEFORE the " +
                    "deploy now disagree with staging. The server moved; every client on the network " +
                    "is now expanding a different grid than the one it will be served.\n\n" +
                    regressions.joinToString("\n"),
            )
        }
    }

    /**
     * The expansion inputs themselves must survive a deploy untouched. `series` is the frozen
     * `DTSTART` + zone the whole offline story rests on (ADR-0053 decision 2) — a migration that
     * re-anchors one silently re-cuts every window a client has ever cached, and unlike a moved
     * occurrence row that damage is not repairable from the client side.
     */
    @Test
    fun theDeployDidNotRewriteAnySeriesAnchor() {
        val dir = probeDir() ?: return skip("DEFERNO_PROBE_DIR is not set")
        val pre = casesIn(dir, "pre") ?: return skip("no pre capture")
        val post = casesIn(dir, "post") ?: return skip("no post capture")

        val moved = post.mapNotNull { (name, after) ->
            val before = pre[name] ?: return@mapNotNull null
            val b = before["series"]?.jsonObject
            val a = after["series"]?.jsonObject
            if (b == a) null else "  $name\n    pre : $b\n    post: $a"
        }
        assertEquals(
            emptyList(), moved,
            "the deploy rewrote the frozen series inputs of ${moved.size} definition(s)",
        )
    }

    // ── comparing one case ────────────────────────────────────────────────────────────────────────

    private class Verdict(val agrees: Boolean, val summary: String, val detail: String)

    /**
     * The one divergence the probe measured on staging BEFORE #658, so it reads as itself rather
     * than as a mystery: when a rule yields no firing inside the window, `/tasks/calendar` still
     * emits a single row at the definition's own `complete_by` — its deadline, not a grid slot —
     * while [expandOccurrenceGrid], which models the rule and nothing else, yields nothing.
     *
     * Measured over 34 seeded definitions on 2026-08-05: the seven that produced no firing in
     * their window each returned exactly one row, at the anchor instant, every time. Naming it
     * keeps the post-deploy report honest — an explained standing mismatch is not a regression,
     * and a regression must not be able to hide inside this bucket.
     */
    private fun isDeadlineRowNotAFiring(serverOnly: Set<Instant>, clientCount: Int, anchor: Instant) =
        clientCount == 0 && serverOnly.size == 1 && serverOnly.first() == anchor

    /**
     * Expand the case's rule offline and line it up against the instants staging's calendar returned.
     *
     * Both sides are reduced to a sorted set of UTC instants. The client side projects each firing's
     * local wall time through the series' **frozen** zone — never the device's — because that is the
     * zone the grid was cut in. Cancelled firings are dropped: the expander still reports them (an
     * override needs the slot), the feed does not.
     *
     * The feed's window is half-open `[start, end)` while [expandOccurrenceGrid]'s is inclusive, so
     * the last day is excluded here rather than counted as a phantom disagreement.
     */
    private fun compare(case: JsonObject): Verdict {
        val series = case.getValue("series").jsonObject
        val window = case.getValue("window").jsonObject
        val tzid = series.str("tzid")
        val zone = runCatching { TimeZone.of(tzid) }.getOrNull()
            ?: return Verdict(false, "unknown zone '$tzid' on this JVM", "")
        val to = LocalDate.parse(window.str("to"))

        val expansion = expandOccurrenceGrid(
            recurrence = case.getValue("recurrence").jsonObject.toRecurrence(),
            series = SeriesInputs(
                anchorLocal = LocalDateTime.parse(series.str("dtstart_local")),
                tzid = tzid,
                untilUtc = series["until_utc"]?.takeUnless { it.isNull() }
                    ?.let { Instant.parse(it.jsonPrimitive.content) },
                exdates = series["exdates"]?.jsonArray.orEmpty()
                    .map { LocalDateTime.parse(it.jsonPrimitive.content) },
                overrides = series["overrides"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
                    SeriesOverride(
                        recurrenceId = LocalDateTime.parse(it.str("recurrence_id")),
                        isCancelled = it.getValue("is_cancelled").jsonPrimitive.boolean,
                        movedToLocal = it["moved_to_local"]?.takeUnless { m -> m.isNull() }
                            ?.let { m -> LocalDateTime.parse(m.jsonPrimitive.content) },
                    )
                },
            ),
            from = LocalDate.parse(window.str("from")),
            to = to,
        )

        val server = case.getValue("server_starts_utc").jsonArray
            .map { Instant.parse(it.jsonPrimitive.content) }
            .toSortedSet()

        val anchor = LocalDateTime.parse(series.str("dtstart_local")).toInstant(zone)

        val client = when (expansion) {
            is Expansion.NotExpandable -> {
                // The client refuses to draw this grid at all. Agreement then means the server
                // served nothing either; anything else is a real divergence the user would see as
                // rows the app cannot account for.
                val why = expansion.reason::class.simpleName
                return if (isDeadlineRowNotAFiring(server, clientCount = 0, anchor = anchor)) {
                    Verdict(false, "client refuses ($why); server's 1 row is the complete_by deadline", "")
                } else {
                    Verdict(
                        server.isEmpty(),
                        "client refuses ($why), server returned ${server.size} firing(s)",
                        server.take(5).joinToString(", "),
                    )
                }
            }
            is Expansion.Firings -> expansion.firings
                .filterNot { it.isCancelled }
                .filter { it.startLocal.date < to }
                .map { it.startLocal.toInstant(zone) }
                .toSortedSet()
        }

        val onlyServer = server - client
        val onlyClient = client - server
        if (isDeadlineRowNotAFiring(onlyServer, client.size, anchor)) {
            return Verdict(false, "rule fires nowhere here; server's 1 row is the complete_by deadline", "")
        }
        return Verdict(
            agrees = onlyServer.isEmpty() && onlyClient.isEmpty(),
            summary = "server ${server.size} / client ${client.size}; " +
                "${onlyServer.size} server-only, ${onlyClient.size} client-only",
            detail = buildString {
                if (onlyServer.isNotEmpty()) appendLine("      server-only: ${onlyServer.take(6)}")
                if (onlyClient.isNotEmpty()) appendLine("      client-only: ${onlyClient.take(6)}")
            },
        )
    }

    // ── reading the probe's captures ──────────────────────────────────────────────────────────────

    private fun probeDir(): File? = System.getenv("DEFERNO_PROBE_DIR")
        ?.let(::File)
        ?.takeIf { it.isDirectory }

    private fun casesIn(dir: File, label: String): Map<String, JsonObject>? {
        val file = File(dir, "grid-cases-$label.json").takeIf { it.isFile } ?: return null
        return json.parseToJsonElement(file.readText()).jsonArray
            .map { it.jsonObject }
            .associateBy { it.str("name") }
    }

    private fun skip(why: String) = println("StagingGridParityTest: skipped — $why")

    private fun render(name: String, verdict: String, case: JsonObject, v: Verdict) = buildString {
        appendLine("── $name  [$verdict]")
        appendLine("   ${case.str("note")}")
        appendLine("   rule:   ${case.getValue("recurrence")}")
        appendLine("   series: ${case.getValue("series")}")
        appendLine("   ${v.summary}")
        append(v.detail)
    }

    private fun banner(
        preCount: Int,
        postCount: Int,
        regressions: List<String>,
        standing: List<String>,
        healed: List<String>,
        onlyPre: Set<String>,
        onlyPost: Set<String>,
    ) = buildString {
        appendLine()
        appendLine("── offline expander vs staging ($preCount pre / $postCount post cases) ──")
        appendLine("   regressed (agreed pre, disagrees post) : ${regressions.size}")
        appendLine("   standing mismatches (both sides)       : ${standing.size}")
        appendLine("   healed (disagreed pre, agrees post)    : ${healed.size}")
        if (onlyPre.isNotEmpty()) appendLine("   present only in the pre capture       : $onlyPre")
        if (onlyPost.isNotEmpty()) appendLine("   present only in the post capture      : $onlyPost")
        standing.forEach { appendLine("     · $it") }
        healed.forEach { appendLine("     ✓ $it") }
    }

    // ── the corpus case reader, verbatim from RecurrenceCorpusTest ────────────────────────────────
    //
    // Deliberately duplicated rather than shared: the probe emits corpus-SHAPED cases so both readers
    // stay a plain function of the same documented file format. Extracting a helper would couple the
    // golden-corpus test — which must keep working with no probe on disk — to this harness.

    private fun JsonObject.toRecurrence(): Recurrence = Recurrence(
        cadence = when (val type = str("type")) {
            "daily" -> Cadence.Daily
            "every_n_days" -> Cadence.EveryNDays(int("n"))
            "weekly" -> Cadence.Weekly(getValue("days").jsonArray.map { it.jsonPrimitive.content })
            "monthly" -> Cadence.Monthly(
                interval = int("interval"),
                on = getValue("on").jsonObject.let { on ->
                    when (val anchor = on.str("type")) {
                        "day_of_month" -> MonthlyAnchor.DayOfMonth(on.int("day"))
                        "nth_weekday" -> MonthlyAnchor.NthWeekday(on.int("nth"), on.str("weekday"))
                        else -> fail("unknown monthly anchor '$anchor'")
                    }
                },
            )
            "yearly" -> Cadence.Yearly(int("interval"), int("month"), int("day"))
            else -> fail("unknown cadence '$type'")
        },
        bound = this["end"]?.takeUnless { it.isNull() }?.jsonObject?.let { end ->
            when (val type = end.str("type")) {
                "never" -> RecurrenceBound.Never
                "on_date" -> RecurrenceBound.OnDate(LocalDate.parse(end.str("date")))
                "after_count" -> RecurrenceBound.AfterCount(end.int("n"))
                else -> fail("unknown bound '$type'")
            }
        } ?: RecurrenceBound.Never,
    )

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun kotlinx.serialization.json.JsonElement.isNull() =
        this is kotlinx.serialization.json.JsonNull
}

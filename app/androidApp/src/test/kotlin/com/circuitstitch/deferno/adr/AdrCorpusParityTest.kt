package com.circuitstitch.deferno.adr

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the ADR corpus (`docs/adr/NNNN-*.md`) mechanically honest, so the house rules in
 * `docs/adr/TEMPLATE.md` cannot decay the way the "one term, one concept" rule in
 * `docs/agents/domain.md` decayed — that rule has been written down since the docs landed and nothing
 * ever checked it, so the corpus drifted anyway. This is the ADR-side sibling of
 * `L10nCatalogParityTest`: pure file IO plus set/count math, no Android or Robolectric, so it runs on
 * the JVM-fast `check` path CI already invokes.
 *
 * Two enforcement shapes, because most of these rules cannot be green on a corpus written before they
 * existed:
 *  - **Hard rules** fail outright. Every one of them is green today and must stay green: unique
 *    numbering, resolvable `ADR-NNNN` citations, resolvable relative links, the section spellings, and
 *    a Status value inside the vocabulary.
 *  - **Grandfathered rules** carry a shrink-only ledger beside the corpus, on the
 *    `app/shared-l10n/l10n-parity-overrides.txt` model:
 *      - `docs/adr/adr-hygiene-exemptions.txt` — named exemptions. A NEW violation fails; a listed one
 *        passes; a line whose violation is gone fails as stale, so the list can only shrink.
 *      - `docs/adr/adr-hygiene-baseline.txt` — per-file violation counts for the four prose rules that
 *        breach in bulk today (long sentences, contractions, semicolons, long lines). A count that
 *        GROWS fails. A count that shrinks is reported, not failed, so improving prose never breaks CI
 *        — but the number may then only be edited downward, never back up.
 *
 * Deliberately NOT mechanised — do not assume the gate covers these:
 *  - **"An identifier an ADR names as current still exists in the tree."** There is no regex for
 *    "names as current": an ADR cites dead class names in its Context and its Considered & rejected
 *    section on purpose (policy A0 keeps the argument immutable), so a symbol-existence sweep would
 *    flag the record's own history. If this is ever wanted, it needs a hand-curated watchlist file of
 *    (ADR, identifier) pairs, not inference.
 *  - **The `CONTEXT.md` `_Avoid_` terminology check.** The banned synonyms are ordinary English words
 *    ("screen", "status", "view", "profile") that appear legitimately in prose, in code identifiers and
 *    inside the very `_Avoid_` lines that ban them. Every implementation tried drowns in false
 *    positives; the one-term-one-concept rule stays a review responsibility.
 *  - **Compound supersession claims.** `everySupersessionClaimHasABackPointer` matches
 *    `<verb> ADR-NNNN`; it does not follow an "… and **ADR-NNNN**" continuation (ADR-0033 line 17
 *    amends two records that way and only the first is detected). Widening the verb-to-target distance
 *    produced false positives on ordinary prose, so the check under-reports rather than lies.
 */
class AdrCorpusParityTest {

    // ---------------------------------------------------------------- corpus + repo location

    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: error("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")

    private val adrDir: File = repoRoot.resolve("docs/adr")

    /** The corpus: `NNNN-slug.md` only — `TEMPLATE.md` and the ledger files are not records. */
    private val adrs: List<File> =
        adrDir.listFiles()!!.filter { it.isFile && ADR_FILE_NAME.matches(it.name) }.sortedBy { it.name }

    private val adrsByNumber: Map<String, File> = adrs.groupBy { it.name.take(4) }
        .mapValues { (_, files) -> files.first() }

    private val texts: Map<File, String> = adrs.associateWith { it.readText() }

    // ---------------------------------------------------------------- shrink-only ledgers

    private val exemptions: Map<String, List<String>> = readLedger("docs/adr/adr-hygiene-exemptions.txt")
        .map { (raw, line) ->
            val parts = line.split(Regex("""\s+"""), limit = 2)
            require(parts.size == 2) { "malformed exemption line: '$raw'" }
            require(parts[0] in EXEMPTION_KINDS) {
                "unknown exemption kind '${parts[0]}' in line: '$raw' (known: $EXEMPTION_KINDS)"
            }
            parts[0] to parts[1].trim().replace(Regex("""\s+"""), " ")
        }
        .groupBy({ it.first }, { it.second })

    /** `rule -> (adr file name -> allowed violation count)`. */
    private val baseline: Map<String, Map<String, Int>> = readLedger("docs/adr/adr-hygiene-baseline.txt")
        .map { (raw, line) ->
            val parts = line.split(Regex("""\s+"""))
            require(parts.size == 3) { "malformed baseline line (want '<rule> <file> <count>'): '$raw'" }
            require(parts[0] in BASELINE_RULES) {
                "unknown baseline rule '${parts[0]}' in line: '$raw' (known: $BASELINE_RULES)"
            }
            val count = parts[2].toIntOrNull() ?: error("non-numeric baseline count in line: '$raw'")
            Triple(parts[0], parts[1], count)
        }
        .groupBy { it.first }
        .mapValues { (_, rows) -> rows.associate { it.second to it.third } }

    private fun exempt(kind: String): List<String> = exemptions[kind].orEmpty()

    private fun readLedger(path: String): List<Pair<String, String>> =
        repoRoot.resolve(path).readLines()
            .map { it to it.substringBefore('#').trim() }
            .filter { it.second.isNotEmpty() }

    // ================================================================ 1. numbering

    @Test
    fun adrNumbersAreUnique() {
        val duplicates = adrs.groupBy { it.name.take(4) }.filterValues { it.size > 1 }
            .toSortedMap()
            .map { (number, files) -> "ADR-$number claimed by: " + files.joinToString(", ") { it.name } }
        assertTrue(
            "Two records claim the same ADR number. A number is an address 3,001 citations depend on, " +
                "so rename the LATER record to the next free number and sweep its citations " +
                "(TEMPLATE.md §4 — allocate by adding the row to the index in the same commit):\n" +
                duplicates.joinToString("\n"),
            duplicates.isEmpty(),
        )
    }

    // ================================================================ 2. citations resolve

    @Test
    fun everyAdrCitationResolvesToOneRecord() {
        var total = 0
        var citingFiles = 0
        val dangling = mutableListOf<String>()
        citableFiles().forEach { file ->
            var seen = false
            file.readLines().forEachIndexed { idx, line ->
                CITATION.findAll(line).forEach { m ->
                    total++
                    seen = true
                    if (m.groupValues[1] !in adrsByNumber) {
                        dangling += "${file.relativeTo(repoRoot)}:${idx + 1}  ${m.value}"
                    }
                }
            }
            if (seen) citingFiles++
        }
        println("[adr-gate] $total ADR-NNNN citations across $citingFiles git-tracked files resolve.")
        assertTrue(
            "ADR-NNNN citations that name no record in docs/adr. Either the record was renumbered " +
                "(sweep the citation to the new number) or the number was never allocated (fix the " +
                "typo). Never leave a citation pointing at nothing — it reads as law that cannot be " +
                "found:\n" + dangling.joinToString("\n"),
            dangling.isEmpty(),
        )
    }

    // ================================================================ 3. status line

    @Test
    fun everyAdrHasAStatusLineInTheVocabulary() {
        val problems = mutableListOf<String>()
        val missingExempt = exempt("status-missing")
        val spellingExempt = exempt("status-spelling")

        adrs.forEach { adr ->
            val lines = texts.getValue(adr).lines()
            val hit = lines.withIndex().firstOrNull { STATUS_LINE.containsMatchIn(it.value) }
            if (hit == null) {
                if (adr.name !in missingExempt) {
                    problems += "${adr.name}: no '**Status.**' line — add one directly under the title " +
                        "(TEMPLATE.md §2), or grandfather it as 'status-missing ${adr.name}'"
                }
                return@forEach
            }
            val lineNo = hit.index + 1
            if (!hit.value.startsWith("**Status.**") && adr.name !in spellingExempt) {
                problems += "${adr.name}:$lineNo: '**Status:**' — the house spelling is '**Status.**' " +
                    "with a period, or grandfather it as 'status-spelling ${adr.name}'"
            }
            val value = STATUS_LINE.replace(hit.value, "").trim()
            val match = STATUS_VALUE.find(value)
            if (match == null) {
                problems += "${adr.name}:$lineNo: Status value '${value.take(48)}' is outside the " +
                    "vocabulary — open with one of $STATUS_WORDS (TEMPLATE.md §2); prose may follow it"
            } else {
                val target = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                if (target != null && target !in adrsByNumber) {
                    problems += "${adr.name}:$lineNo: Status points at ADR-$target, which does not " +
                        "exist — point it at the real successor"
                }
            }
        }
        assertTrue(
            "Status lines that do not carry a resolvable, in-vocabulary value. A record with no Status " +
                "reads as current law forever, which is how a reversed decision gets cited years " +
                "later:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ================================================================ 4. section shape

    /**
     * Required sections, one spelling each. `**Considered & rejected.**` IS required — the ampersand
     * form is the corpus plurality and the alternative-spelling check below keeps it the only one — with
     * the 8 records that genuinely have no rejected-alternatives section grandfathered by name in the
     * exemption file. Requiring it (rather than treating it as optional) is what makes a NEW record
     * without one fail; the alternative, dropping the rule, would let the section quietly disappear.
     */
    @Test
    fun requiredSectionsUseTheOneHouseSpelling() {
        val problems = mutableListOf<String>()
        val sectionExempt = exempt("section-missing")
        val headingExempt = exempt("heading-shape")

        adrs.forEach { adr ->
            val text = texts.getValue(adr)
            REQUIRED_SECTIONS.forEach { section ->
                val marker = "**$section.**"
                if (!text.contains(marker) && "${adr.name} $marker" !in sectionExempt) {
                    problems += "${adr.name}: missing '$marker' — add the section, or grandfather it " +
                        "as 'section-missing ${adr.name} $marker'"
                }
            }
            text.lines().forEachIndexed { idx, line ->
                BANNED_SECTION_SPELLINGS.forEach { banned ->
                    if (line.contains(banned)) {
                        problems += "${adr.name}:${idx + 1}: '$banned' — the corpus has exactly one " +
                            "spelling per section; use the '**Name.**' bold pseudo-header form"
                    }
                }
                if (HOUSE_SECTION_HEADING.containsMatchIn(line) && adr.name !in headingExempt) {
                    problems += "${adr.name}:${idx + 1}: '${line.trim()}' is a markdown heading for a " +
                        "house section — all 50 records use bold pseudo-headers ('**Context.**' inline " +
                        "at the start of its paragraph), which is what makes the corpus greppable; or " +
                        "grandfather it as 'heading-shape ${adr.name}'"
                }
            }
        }
        assertTrue(
            "Section markers off the house shape:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ================================================================ 5. reciprocal back-pointers

    @Test
    fun everySupersessionClaimHasABackPointer() {
        val problems = mutableListOf<String>()
        val backPointerExempt = exempt("backpointer")
        var claims = 0
        adrs.forEach { adr ->
            val source = adr.name.take(4)
            texts.getValue(adr).lines().forEachIndexed { idx, line ->
                SUPERSESSION_CLAIM.findAll(line).forEach { m ->
                    claims++
                    val target = m.groupValues[2]
                    val key = "$source->$target"
                    val targetFile = adrsByNumber[target]
                    val hasBackPointer =
                        targetFile != null && texts.getValue(targetFile).contains("ADR-$source")
                    if (!hasBackPointer && key !in backPointerExempt) {
                        problems += "${adr.name}:${idx + 1}: claims '${m.value}' but ADR-$target never " +
                            "mentions ADR-$source — mark the target in the same commit (TEMPLATE.md §5: " +
                            "the Status line gains the pointer back, and a reversed bullet gains its " +
                            "in-place marker — the two proven forms are 0016 line 3 and 0008 line 16), " +
                            "or grandfather it as 'backpointer $key'"
                    }
                }
            }
        }
        println("[adr-gate] $claims supersedes/amends/refines claims found.")
        assertTrue(
            "Forward supersession claims with no back-pointer. An unmarked target reads as current " +
                "law:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ================================================================ 6. wikilinks

    @Test
    fun everyWikilinkResolvesToAContextTerm() {
        val defined = contextTerms().map(::normalizeTerm).toSet()
        val allowed = (exempt("wikilink-inherited") + exempt("wikilink-undefined")).map(::normalizeTerm)
        val problems = mutableListOf<String>()
        wikilinkSites().forEach { (term, sites) ->
            val n = normalizeTerm(term)
            if (n in defined || n.removeSuffix("s") in defined || n in allowed) return@forEach
            problems += "[[$term]] (${sites.take(3).joinToString(", ")}${if (sites.size > 3) ", …" else ""})"
        }
        assertTrue(
            "Wikilinks that resolve to no CONTEXT.md term. Either define the term in CONTEXT.md in the " +
                "same commit (TEMPLATE.md §7), drop the brackets if it is a code identifier rather than " +
                "a domain term, or declare it in docs/adr/adr-hygiene-exemptions.txt as " +
                "'wikilink-inherited <Term>' (owned by the backend glossary) / 'wikilink-undefined " +
                "<Term>' (grandfathered, must shrink):\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ================================================================ 7. relative links

    @Test
    fun everyRelativeMarkdownLinkResolves() {
        val problems = mutableListOf<String>()
        adrs.forEach { adr ->
            texts.getValue(adr).lines().forEachIndexed { idx, line ->
                MARKDOWN_LINK.findAll(line).forEach { m ->
                    val target = m.groupValues[1]
                    if (target.isEmpty() || ABSOLUTE_URI.containsMatchIn(target)) return@forEach
                    if (!adrDir.resolve(target).exists() && !repoRoot.resolve(target).exists()) {
                        problems += "${adr.name}:${idx + 1}: ]($target) does not exist"
                    }
                }
            }
        }
        assertTrue(
            "Relative links in docs/adr pointing at files that do not exist — repoint them (a renamed " +
                "record is the usual cause) or drop the link:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ================================================================ 8-11. ratcheted prose rules

    /**
     * 60 words, not 20 or 25: at 20 words 760 of 1,773 corpus sentences breach and at 25 words 554 do,
     * which is a rewrite of the whole corpus rather than a hygiene rule. 60 isolates the genuinely
     * unreadable ones (31 today, worst 117 words) and leaves ordinary technical prose alone.
     */
    @Test
    fun longSentencesDoNotIncrease() = ratchet(
        rule = "long-sentences",
        what = "sentences over $MAX_SENTENCE_WORDS words",
        advice = "split the sentence; a 60-word sentence has more than one claim in it",
    ) { adr ->
        proseUnits(texts.getValue(adr)).flatMap { (line, unit) ->
            sentences(unit).filter { wordCount(it) > MAX_SENTENCE_WORDS }
                .map { "$line: ${wordCount(it)}w  ${it.trim().take(72)}…" }
        }
    }

    /**
     * Contractions only — NOT a bare apostrophe sweep. `[A-Za-z]+'` matches 296 sites in docs/adr and
     * over 200 of them are legitimate possessives (`client's`, `server's`, `today's`), so the pattern is
     * the closed contraction suffix set plus the six pronoun+`'s` forms, with inline code and fenced
     * code masked out first and `TDD'd` allowlisted.
     */
    @Test
    fun contractionsDoNotIncrease() = ratchet(
        rule = "contractions",
        what = "contractions",
        advice = "write it out: \"does not\", \"cannot\", \"it is\"",
    ) { adr ->
        maskedLines(texts.getValue(adr)).flatMapIndexed { idx, line ->
            CONTRACTION.findAll(line)
                .filter { it.value.lowercase() !in CONTRACTION_ALLOWLIST }
                .map { "${idx + 1}: ${it.value}" }
                .toList()
        }
    }

    /**
     * A second semicolon in a paragraph means a second sentence. 485 semicolons live in 50 of 50
     * records, so this can only ever be a ratchet — 113 paragraphs carry more than one today.
     */
    @Test
    fun multiSemicolonParagraphsDoNotIncrease() = ratchet(
        rule = "semicolon-paragraphs",
        what = "paragraphs with more than one semicolon",
        advice = "promote the second clause to its own sentence",
    ) { adr ->
        paragraphs(texts.getValue(adr))
            .map { (line, body) -> line to INLINE_CODE.replace(body, "").count { it == ';' } }
            .filter { it.second > 1 }
            .map { "${it.first}: ${it.second} semicolons in one paragraph" }
    }

    @Test
    fun longLinesDoNotIncrease() = ratchet(
        rule = "long-lines",
        what = "lines over $MAX_LINE_LENGTH characters",
        advice = "hard-wrap prose (TEMPLATE.md §6 targets ~100 columns; $MAX_LINE_LENGTH is the " +
            "ceiling) — but never reflow a paragraph you did not otherwise change",
    ) { adr ->
        texts.getValue(adr).lines().mapIndexedNotNull { idx, line ->
            if (line.length > MAX_LINE_LENGTH) "${idx + 1}: ${line.length} chars" else null
        }
    }

    /**
     * Runs one grandfathered prose rule. A count above its baseline fails with every offending site.
     * A count below is printed, not failed — improving prose must never break CI — and the printed
     * line is the exact replacement for the ledger, which is the only direction that ledger may move.
     */
    private fun ratchet(rule: String, what: String, advice: String, violations: (File) -> List<String>) {
        val allowed = baseline[rule].orEmpty()
        val regressions = mutableListOf<String>()
        val improvements = mutableListOf<String>()
        var total = 0
        adrs.forEach { adr ->
            val found = violations(adr)
            total += found.size
            val budget = allowed[adr.name] ?: 0
            if (found.size > budget) {
                regressions += "${adr.name}: ${found.size} $what, baseline allows $budget" +
                    found.joinToString("\n      - ", prefix = "\n      - ")
            } else if (found.size < budget) {
                improvements += "  $rule ${adr.name} ${found.size}   (was $budget)"
            }
        }
        allowed.keys.filter { name -> adrs.none { it.name == name } }.sorted().forEach {
            regressions += "baseline names '$it', which is not a record in docs/adr — delete the line"
        }
        println(
            "[adr-gate] $rule: $total $what across ${adrs.size} records " +
                "(baseline allows ${allowed.values.sum()})."
        )
        if (improvements.isNotEmpty()) {
            println(
                "[adr-gate] $rule improved — the ratchet may now be tightened. Replace these lines in " +
                    "docs/adr/adr-hygiene-baseline.txt (delete any that reach 0):\n" +
                    improvements.sorted().joinToString("\n")
            )
        }
        assertTrue(
            "NEW $what in docs/adr. The baseline in docs/adr/adr-hygiene-baseline.txt is shrink-only: " +
                "never raise a number to accommodate new prose. Fix the writing — $advice:\n" +
                regressions.joinToString("\n"),
            regressions.isEmpty(),
        )
    }

    // ================================================================ 12. the ledgers may only shrink

    @Test
    fun noStaleExemptions() {
        val stale = mutableListOf<String>()
        fun adrOf(name: String): File? = adrs.firstOrNull { it.name == name }

        exempt("status-missing").forEach { name ->
            val adr = adrOf(name)
            if (adr == null) stale += "status-missing $name  (no such record)"
            else if (texts.getValue(adr).lines().any { STATUS_LINE.containsMatchIn(it) }) {
                stale += "status-missing $name  (it has a Status line now)"
            }
        }
        exempt("status-spelling").forEach { name ->
            val adr = adrOf(name)
            if (adr == null) stale += "status-spelling $name  (no such record)"
            else if (texts.getValue(adr).lines().none { it.startsWith("**Status:**") }) {
                stale += "status-spelling $name  (it uses '**Status.**' now)"
            }
        }
        exempt("section-missing").forEach { payload ->
            val name = payload.substringBefore(' ')
            val marker = payload.substringAfter(' ').trim()
            val adr = adrOf(name)
            if (adr == null) stale += "section-missing $payload  (no such record)"
            else if (texts.getValue(adr).contains(marker)) {
                stale += "section-missing $payload  (the section is present now)"
            }
        }
        exempt("heading-shape").forEach { name ->
            val adr = adrOf(name)
            if (adr == null) stale += "heading-shape $name  (no such record)"
            else if (texts.getValue(adr).lines().none { HOUSE_SECTION_HEADING.containsMatchIn(it) }) {
                stale += "heading-shape $name  (it uses bold pseudo-headers now)"
            }
        }
        exempt("backpointer").forEach { key ->
            val source = key.substringBefore("->")
            val target = key.substringAfter("->")
            val sourceFile = adrsByNumber[source]
            val targetFile = adrsByNumber[target]
            when {
                sourceFile == null || targetFile == null -> stale += "backpointer $key  (unknown record)"
                texts.getValue(targetFile).contains("ADR-$source") ->
                    stale += "backpointer $key  (ADR-$target back-points now)"
                !SUPERSESSION_CLAIM.findAll(texts.getValue(sourceFile))
                    .any { it.groupValues[2] == target } ->
                    stale += "backpointer $key  (ADR-$source no longer claims it)"
            }
        }
        val defined = contextTerms().map(::normalizeTerm).toSet()
        val linked = wikilinkSites().keys.map(::normalizeTerm).toSet()
        (exempt("wikilink-inherited") + exempt("wikilink-undefined")).forEach { term ->
            val n = normalizeTerm(term)
            when {
                n in defined -> stale += "wikilink $term  (CONTEXT.md defines it now)"
                n !in linked -> stale += "wikilink $term  (nothing links to it any more)"
            }
        }

        assertTrue(
            "Stale docs/adr/adr-hygiene-exemptions.txt lines — the violation each one covers is gone. " +
                "Delete them. This is what makes the ledger shrink-only instead of rotting into a " +
                "permanent list of things nobody checks:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Git-tracked `.md`/`.kt`/`.kts`/`.swift` files. Git-tracked and not a tree walk on purpose: the
     * build output holds generated `Deferno.h` framework headers that duplicate every KDoc citation and
     * would inflate the count roughly eightfold. Falls back to a pruned walk if git is unavailable.
     */
    private fun citableFiles(): List<File> {
        val tracked = runCatching {
            val process = ProcessBuilder("git", "ls-files", "-z")
                .directory(repoRoot).redirectErrorStream(false).start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ls-files failed" }
            out.split('\u0000').filter { it.isNotEmpty() }
        }.getOrNull()

        val paths = tracked
            ?: repoRoot.walkTopDown()
                .onEnter { it.name !in PRUNED_DIRS }
                .filter { it.isFile }
                .map { it.relativeTo(repoRoot).path }
                .toList()

        return paths.filter { path -> CITABLE_EXTENSIONS.any { path.endsWith(it) } }
            .map { repoRoot.resolve(it) }
            .filter { it.isFile }
    }

    /** Terms `CONTEXT.md` defines: `**Term** …:` definition openers, bold list items, and headings. */
    private fun contextTerms(): Set<String> {
        val terms = mutableSetOf<String>()
        repoRoot.resolve("CONTEXT.md").readLines().forEach { line ->
            CONTEXT_DEFINITION.find(line)?.let { terms += it.groupValues[1].trim() }
            CONTEXT_BULLET_TERM.find(line)?.let { terms += it.groupValues[1].trim() }
            CONTEXT_HEADING.find(line)?.let { terms += it.groupValues[1].trim() }
        }
        return terms
    }

    /** `[[Term]]` -> the `file:line` sites that link it, over docs/adr plus CONTEXT.md itself. */
    private fun wikilinkSites(): Map<String, List<String>> {
        val sites = mutableMapOf<String, MutableList<String>>()
        (adrs + repoRoot.resolve("CONTEXT.md")).forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                WIKILINK.findAll(line).forEach { m ->
                    sites.getOrPut(m.groupValues[1].trim()) { mutableListOf() } += "${file.name}:${idx + 1}"
                }
            }
        }
        return sites
    }

    private fun normalizeTerm(term: String): String = term.lowercase().filter { it.isLetterOrDigit() }

    /** Blank-line-separated blocks, fenced code dropped, as `startLine to joined body`. */
    private fun paragraphs(text: String): List<Pair<Int, String>> {
        val blocks = mutableListOf<Pair<Int, String>>()
        var buffer = mutableListOf<String>()
        var start = 0
        var inFence = false
        fun flush() {
            if (buffer.isNotEmpty()) blocks += start to buffer.joinToString(" ") { it.trim() }
            buffer = mutableListOf()
        }
        text.lines().forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("```") -> { inFence = !inFence; flush() }
                inFence -> Unit
                trimmed.isEmpty() -> flush()
                else -> {
                    if (buffer.isEmpty()) start = idx + 1
                    buffer += raw
                }
            }
        }
        flush()
        return blocks
    }

    /**
     * Prose units for sentence counting: one per list item, one per paragraph. Fenced code, table rows,
     * markdown headings and rules are skipped — a table cell is not a sentence, and a bullet that ends
     * with no period is one unit rather than a run-on merged with the next bullet.
     */
    private fun proseUnits(text: String): List<Pair<Int, String>> {
        val units = mutableListOf<Pair<Int, String>>()
        var buffer = mutableListOf<String>()
        var start = 0
        var inFence = false
        fun flush() {
            if (buffer.isNotEmpty()) units += start to buffer.joinToString(" ") { it.trim() }
            buffer = mutableListOf()
        }
        text.lines().forEachIndexed { idx, raw ->
            val line = idx + 1
            val body = raw.replace(BLOCKQUOTE_MARKER, "")
            val trimmed = body.trim()
            when {
                trimmed.startsWith("```") -> { inFence = !inFence; flush() }
                inFence -> Unit
                trimmed.isEmpty() || trimmed.startsWith("|") || trimmed.startsWith("#") ||
                    HORIZONTAL_RULE.matches(trimmed) -> flush()
                LIST_MARKER.containsMatchIn(body) -> {
                    flush()
                    buffer += LIST_MARKER.replace(body, "")
                    start = line
                }
                else -> {
                    if (buffer.isEmpty()) start = line
                    buffer += body
                }
            }
        }
        flush()
        return units
    }

    /**
     * Splits a prose unit into sentences. Inline code spans, abbreviation periods (`e.g.`) and decimal
     * points are masked to equal-length placeholders first, so the split offsets still index the
     * original text and a version number cannot end a sentence.
     */
    private fun sentences(unit: String): List<String> {
        val masked = maskForSentenceSplit(unit)
        val cuts = mutableListOf(0)
        SENTENCE_BREAK.findAll(masked).forEach { cuts += it.range.last + 1 }
        cuts += unit.length
        return cuts.zipWithNext { from, to -> unit.substring(from, to) }.filter { it.isNotBlank() }
    }

    private fun maskForSentenceSplit(unit: String): String {
        val chars = unit.toCharArray()
        INLINE_CODE.findAll(unit).forEach { m -> for (i in m.range) chars[i] = 'x' }
        var masked = String(chars)
        ABBREVIATIONS.forEach { abbreviation ->
            var from = masked.indexOf(abbreviation, ignoreCase = true)
            while (from >= 0) {
                masked = masked.substring(0, from) + abbreviation.replace('.', '~') +
                    masked.substring(from + abbreviation.length)
                from = masked.indexOf(abbreviation, from + 1, ignoreCase = true)
            }
        }
        return DECIMAL_POINT.replace(masked, "~")
    }

    /** Words = whitespace tokens holding a letter or digit; a code span or wikilink counts as one. */
    private fun wordCount(sentence: String): Int =
        sentence.replace(INLINE_CODE, " code ")
            .replace(WIKILINK, "$1")
            .replace(MARKDOWN_LINK_TEXT, "$1")
            .split(Regex("""\s+"""))
            .count { token -> token.any { it.isLetterOrDigit() } }

    /** Lines with fenced-code bodies blanked and inline code stripped — for the contraction sweep. */
    private fun maskedLines(text: String): List<String> {
        var inFence = false
        return text.lines().map { raw ->
            if (raw.trim().startsWith("```")) { inFence = !inFence; return@map "" }
            if (inFence) "" else INLINE_CODE.replace(raw, " ")
        }
    }

    private companion object {
        const val MAX_SENTENCE_WORDS = 60
        const val MAX_LINE_LENGTH = 110

        val ADR_FILE_NAME = Regex("""^\d{4}-.+\.md$""")
        val CITATION = Regex("""ADR-(\d{4})""")
        val STATUS_LINE = Regex("""^\*\*Status[.:]\*\*""")
        val STATUS_WORDS =
            listOf("Accepted", "Superseded by ADR-NNNN", "Amended by ADR-NNNN", "Historical", "Deferred")
        val STATUS_VALUE = Regex(
            """^(Accepted|(?:Superseded|Amended) by ADR-(\d{4})|Historical|Deferred)\b""",
            RegexOption.IGNORE_CASE,
        )
        val REQUIRED_SECTIONS = listOf("Context", "Decision", "Considered & rejected", "Consequences")
        val BANNED_SECTION_SPELLINGS = listOf(
            "**Context:**", "**Decision:**", "**Consequences:**", "**Rejected.**", "**Rejected:**",
            "**Considered and rejected", "**Alternatives considered", "**Trade-offs.**",
        )
        val HOUSE_SECTION_HEADING =
            Regex("""^#{1,6}\s+(Status|Context|Decision|Considered\b.*rejected|Considered options|Consequences)\b""")
        val SUPERSESSION_CLAIM = Regex(
            """(supersedes?|amends?|refines?|generalis(?:e|es)|generaliz(?:e|es))\s+(?:the\s+)?ADR-(\d{4})""",
            RegexOption.IGNORE_CASE,
        )
        val WIKILINK = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?\]\]""")
        val MARKDOWN_LINK = Regex("""\]\(([^)#\s]*)(?:#[^)]*)?\)""")
        val MARKDOWN_LINK_TEXT = Regex("""\[([^\]]*)\]\([^)]*\)""")
        val ABSOLUTE_URI = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")
        val INLINE_CODE = Regex("""`[^`]*`""")
        val LIST_MARKER = Regex("""^\s{0,6}(?:[-*+]|\d+[.)])\s+""")
        val BLOCKQUOTE_MARKER = Regex("""^\s{0,3}>\s?""")
        val HORIZONTAL_RULE = Regex("""^[-=*_]{3,}$""")
        val DECIMAL_POINT = Regex("""(?<=\d)\.(?=\d)""")
        val SENTENCE_BREAK = Regex("""(?<=[.!?])["')\]*_]*\s+(?=["'(\[*_`]*[A-Z0-9])""")
        val CONTRACTION = Regex(
            """\b[A-Za-z]+'(?:t|ve|ll|m|re|d)\b|\b(?:it|let|that|there|here|what)'s\b""",
            RegexOption.IGNORE_CASE,
        )
        val CONTRACTION_ALLOWLIST = setOf("tdd'd")
        val ABBREVIATIONS = listOf("e.g.", "i.e.", "etc.", "vs.", "cf.", "approx.", "et al.", "No.")
        val CITABLE_EXTENSIONS = listOf(".md", ".kt", ".kts", ".swift")
        val PRUNED_DIRS = setOf(
            "build", ".git", ".gradle", ".idea", ".kotlin", "node_modules", "third_party", "Pods",
            "DerivedData", "xcuserdata",
        )
        val CONTEXT_DEFINITION = Regex("""^\*\*([^*]+)\*\*.*:\s*$""")
        val CONTEXT_BULLET_TERM = Regex("""^\s*[-*]\s+\*\*([^*]+)\*\*""")
        val CONTEXT_HEADING = Regex("""^#{2,6}\s+(.+?)\s*$""")
        val EXEMPTION_KINDS = setOf(
            "status-missing", "status-spelling", "section-missing", "heading-shape", "backpointer",
            "wikilink-inherited", "wikilink-undefined",
        )
        val BASELINE_RULES =
            setOf("long-sentences", "contractions", "semicolon-paragraphs", "long-lines")
    }
}

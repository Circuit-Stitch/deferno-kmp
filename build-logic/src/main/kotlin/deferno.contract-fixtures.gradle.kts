import com.circuitstitch.deferno.gradle.ContractFixturesExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Convention: embed a directory of captured JSON from the repo-root `contracts/` into a module's
// `commonTest` source set as a generated Kotlin object, so a golden-file harness can load every file
// on EVERY KMP target (JVM, Android host, iOS) with no runtime file IO.
//
// Two consumers, and the second is what made this configurable (ADR-0004: abstractions are earned at
// the second consumer):
//   - `core:network` embeds `contracts/fixtures` — the captured response envelopes (#19).
//   - `core:model` embeds `contracts/recurrence-corpus` — the occurrence grid the Rust generated,
//     which pins the offline expander (#401, ADR-0053 decision 5).
// The directory, package and object name come from the `contractFixtures { }` block; see
// `ContractFixturesExtension`. A module hosts exactly ONE set: a Gradle plugin applies once per
// project, and the task name and generated-source directory below are fixed strings.
//
// Why codegen, not test resources: KMP `commonTest` has no portable resource reader (iOS especially),
// and the fixtures live OUTSIDE the module (repo-root `contracts/`). Embedding them as String
// constants keeps the test hermetic and cross-platform while staying SINGLE-SOURCED from the captured
// files — re-capturing a fixture regenerates the constant, so a breaking backend shape change surfaces
// as a failing parse test rather than a silent miss ("capture, don't hand-author",
// contracts/fixtures/README.md; ADR-0006).
//
// Compose ON TOP of `deferno.kmp.library` (which supplies the `commonTest` source set this wires
// into). It applies no external Gradle plugin, so — unlike the other conventions — it needs no
// matching `apply false` in the root build (the INVARIANT in build.gradle.kts / build-logic applies
// only to conventions that apply an external Gradle plugin). The `pluginManager.withPlugin` guard
// keeps the source-set wiring independent of plugin-apply order.

private val contractFixtures = extensions.create<ContractFixturesExtension>("contractFixtures").apply {
    sourceDir.convention(rootProject.layout.projectDirectory.dir("contracts/fixtures"))
    objectName.convention("ContractFixtures")
    // packageName gets NO convention on purpose — see ContractFixturesExtension's KDoc.
}
private val generatedSrcDir = layout.buildDirectory.dir("generated/contract-fixtures/commonTest/kotlin")

private val generateContractFixtures = tasks.register("generateContractFixtures") {
    // Hoisted to task-local Providers here, and resolved with `.get()` only inside the action: the
    // action must not capture the extension object, which is owned by the script-plugin instance the
    // configuration cache cannot serialize (same reason the helpers below are local).
    val inputDir = contractFixtures.sourceDir
    val packageName = contractFixtures.packageName
    val objectName = contractFixtures.objectName
    val outputDir = generatedSrcDir
    // Captured as a plain File so the action never reaches for `rootProject`, which the
    // configuration cache forbids at execution time. Used only to render the provenance comment.
    val rootDirFile = rootProject.layout.projectDirectory.asFile
    // Track only the consumed *.json files — NOT the directory's README — so a doc-only edit to a
    // README doesn't needlessly bust the cache and rerun generation + the downstream commonTest
    // compile. Adding/removing a *.json still re-runs (and cleans stale output).
    inputs.files(inputDir.map { it.asFileTree.matching { include("*.json") } })
        .withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    // Declared as inputs so a rename actually re-runs. Without these Gradle reports UP-TO-DATE after
    // a package/object change and the stale generated file survives — `out.deleteRecursively()` below
    // only runs when the task does.
    inputs.property("packageName", packageName)
    inputs.property("objectName", objectName)
    outputs.dir(outputDir).withPropertyName("generatedSource")
    // Helpers are LOCAL to the task action: a top-level script function would make the action capture
    // the script-plugin object, which the configuration cache cannot serialize.
    doLast {
        // A single Kotlin String constant compiles to one JVM CONSTANT_Utf8 entry, capped at 65535
        // modified-UTF-8 bytes. The fixtures are tiny today (~5KB escaped), but the harness exists to
        // be re-captured — so guard the ceiling and fail with a clear, actionable message rather than
        // the opaque "constant string too long" the Kotlin compiler would emit against generated code.
        val maxLiteralBytes = 60_000
        val pkg = packageName.get()
        val obj = objectName.get()

        // Any character a Kotlin identifier cannot carry becomes `_`, and a leading digit gets a `_`
        // prefix: a corpus case named `29-feb-skip.json` would otherwise emit `val 29_FEB_SKIP`, which
        // does not compile. No fixture has ever started with a digit, which is why this never fired.
        fun constName(fileName: String): String =
            fileName.removeSuffix(".json")
                .map { if (it.isLetterOrDigit() || it == '_') it else '_' }
                .joinToString("")
                .uppercase()
                .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

        // Renders [raw] as a single-line, fully-escaped Kotlin String literal (incl. the quotes).
        fun kotlinStringLiteral(raw: String): String {
            val sb = StringBuilder(raw.length + 16)
            sb.append('"')
            for (c in raw) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '$' -> sb.append("\\").append('$')
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val pkgDir = out.resolve(pkg.replace('.', '/'))
        pkgDir.mkdirs()

        val sourceDirFile = inputDir.get().asFile
        val fixtures = sourceDirFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (fixtures.isEmpty()) {
            error(
                "No *.json files found under $sourceDirFile — the golden-file harness reading " +
                    "$pkg.$obj would be vacuous. Capture them per that directory's README; they are " +
                    "generated or captured, never hand-authored.",
            )
        }

        val code = buildString {
            val relativeSource = sourceDirFile.relativeToOrSelf(rootDirFile).invariantSeparatorsPath
            appendLine("// GENERATED — do not edit by hand.")
            appendLine("// Source: $relativeSource (re-capture per that directory's README).")
            appendLine("// Emitted by the generateContractFixtures task (build-logic: deferno.contract-fixtures).")
            appendLine("package $pkg")
            appendLine()
            appendLine("/**")
            appendLine(" * The captured golden files from `$relativeSource`, embedded verbatim so the harness")
            appendLine(" * over them loads every one on every KMP target with no runtime file IO (#19).")
            appendLine(" */")
            appendLine("internal object $obj {")
            fixtures.forEach { f ->
                val literal = kotlinStringLiteral(f.readText())
                val literalBytes = literal.toByteArray(Charsets.UTF_8).size
                if (literalBytes > maxLiteralBytes) {
                    error(
                        "Fixture '${f.name}' escapes to $literalBytes bytes, over the ~64KB a single Kotlin " +
                            "String constant allows (JVM CONSTANT_Utf8 limit). Chunk it in this generator " +
                            "(runtime concatenation of <64KB pieces) or split the fixture.",
                    )
                }
                append("    val ").append(constName(f.name)).append(": String = ").appendLine(literal)
            }
            appendLine()
            appendLine("    /** Every captured file, keyed by its file name. */")
            appendLine("    val ALL: Map<String, String> = mapOf(")
            fixtures.forEach { f ->
                append("        ").append(kotlinStringLiteral(f.name)).append(" to ")
                append(constName(f.name)).appendLine(",")
            }
            appendLine("    )")
            appendLine("}")
        }
        pkgDir.resolve("$obj.kt").writeText(code)
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.getByType<KotlinMultiplatformExtension>()
        .sourceSets.named("commonTest")
        .configure { kotlin.srcDir(generateContractFixtures) }
}

// `kotlin.srcDir(<taskProvider>)` establishes the implicit task dependency for the Kotlin *compile*
// tasks, but AGP's Android lint enumerates the same commonTest source dirs WITHOUT picking it up —
// so `./gradlew check` (which runs lint) fails Gradle's task-output validation ("uses this output …
// without declaring a dependency") on `generate*LintModel` / `lintAnalyze*`. Make every lint task in
// this module run after the generator. `configureEach` on the live, name-filtered collection also
// catches the lint tasks AGP registers later; in a jvm-only module it matches nothing (harmless).
tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(generateContractFixtures) }

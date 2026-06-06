import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Convention: embed the captured golden envelopes from the repo-root `contracts/fixtures/` into a
// module's `commonTest` source set as a generated Kotlin object, so the contract-fixture harness
// (#19) can load every fixture on EVERY KMP target (JVM, Android host, iOS) with no runtime file IO.
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

private val fixturesDir = rootProject.layout.projectDirectory.dir("contracts/fixtures")
private val generatedSrcDir = layout.buildDirectory.dir("generated/contract-fixtures/commonTest/kotlin")

private val generateContractFixtures = tasks.register("generateContractFixtures") {
    val inputDir = fixturesDir
    val outputDir = generatedSrcDir
    // Track only the consumed *.json files — NOT the directory's README — so a doc-only edit to
    // contracts/fixtures/README.md doesn't needlessly bust the cache and rerun generation + the
    // downstream commonTest compile. Adding/removing a *.json still re-runs (and cleans stale output).
    inputs.files(inputDir.asFileTree.matching { include("*.json") })
        .withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDir).withPropertyName("generatedSource")
    // Helpers are LOCAL to the task action: a top-level script function would make the action capture
    // the script-plugin object, which the configuration cache cannot serialize.
    doLast {
        // A single Kotlin String constant compiles to one JVM CONSTANT_Utf8 entry, capped at 65535
        // modified-UTF-8 bytes. The fixtures are tiny today (~5KB escaped), but the harness exists to
        // be re-captured — so guard the ceiling and fail with a clear, actionable message rather than
        // the opaque "constant string too long" the Kotlin compiler would emit against generated code.
        val maxLiteralBytes = 60_000

        fun constName(fileName: String): String =
            fileName.removeSuffix(".json").replace('-', '_').replace('.', '_').uppercase()

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
        val pkgDir = out.resolve("com/circuitstitch/deferno/core/network/fixtures")
        pkgDir.mkdirs()

        val fixtures = inputDir.asFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (fixtures.isEmpty()) {
            error(
                "No *.json fixtures found under ${inputDir.asFile} — the contract-fixture harness would " +
                    "be vacuous. Expected the captured envelopes (contracts/fixtures/).",
            )
        }

        val code = buildString {
            appendLine("// GENERATED — do not edit by hand.")
            appendLine("// Source: contracts/fixtures (re-capture per contracts/fixtures/README.md).")
            appendLine("// Emitted by the generateContractFixtures task (build-logic: deferno.contract-fixtures).")
            appendLine("package com.circuitstitch.deferno.core.network.fixtures")
            appendLine()
            appendLine("/**")
            appendLine(" * The captured golden envelopes from `contracts/fixtures/`, embedded verbatim so the")
            appendLine(" * contract-fixture harness loads them on every KMP target with no runtime file IO (#19).")
            appendLine(" */")
            appendLine("internal object ContractFixtures {")
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
            appendLine("    /** Every captured fixture, keyed by its file name. */")
            appendLine("    val ALL: Map<String, String> = mapOf(")
            fixtures.forEach { f ->
                append("        ").append(kotlinStringLiteral(f.name)).append(" to ")
                append(constName(f.name)).appendLine(",")
            }
            appendLine("    )")
            appendLine("}")
        }
        pkgDir.resolve("ContractFixtures.kt").writeText(code)
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.getByType<KotlinMultiplatformExtension>()
        .sourceSets.named("commonTest")
        .configure { kotlin.srcDir(generateContractFixtures) }
}

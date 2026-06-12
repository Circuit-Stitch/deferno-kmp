package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The live structured-output tracer (#147) — the truest proof the Koog chain works end to end
 * against a real Anthropic-format endpoint over TLS: typed request in, validated [DraftTasks] out.
 *
 * **Absent-safe (SKIPPED when no key).** It reads `deferno.anthropic.apiKey` / `.baseUrl` from JVM
 * system properties, wired from the gitignored `local.properties` (`core/agent/build.gradle.kts`,
 * the ADR-0012 staging-token pattern), falling back to the conventional `ANTHROPIC_API_KEY` env
 * var. With neither set — CI, and any dev without a key — assumeTrue reports it SKIPPED (never a
 * vacuous pass) and no network call is made. The key is the developer's own credential and is never
 * committed (ADR-0009); pointing `deferno.anthropic.baseUrl` at the Deferno relay exercises the
 * same wire format (ADR-0027).
 *
 * A `jvmTest` (not commonTest) because it makes a real network call through the JVM OkHttp engine.
 */
class AnthropicStructuredOutputIntegrationTest {

    @Test
    fun roundTripsRealStructuredOutputAgainstALiveAnthropicFormatEndpoint() {
        val apiKey = System.getProperty("deferno.anthropic.apiKey").orEmpty()
            .ifBlank { System.getenv("ANTHROPIC_API_KEY").orEmpty() }
        assumeTrue(
            "no inference credential — set deferno.anthropic.apiKey in local.properties (or " +
                "ANTHROPIC_API_KEY) to run the live structured-output tracer (#147)",
            apiKey.isNotBlank(),
        )
        val baseUrl = System.getProperty("deferno.anthropic.baseUrl").orEmpty()
            .ifBlank { AnthropicEndpoint.ANTHROPIC_API_BASE_URL }

        runBlocking {
            val engine = KoogInferenceEngine(
                endpoint = AnthropicEndpoint(baseUrl = baseUrl, credentials = { apiKey }),
            )

            val result = Extractor(engine).extract(
                transcript = Transcript(
                    "I need to mow the lawn, and then sharpen the mower blades before Friday.",
                ),
                today = LocalDate(2026, 6, 8),
                timeZone = "America/Los_Angeles",
            )

            val drafts = assertIs<InferenceResult.Success<DraftTaskProposal>>(result).value.drafts
            assertTrue(drafts.isNotEmpty(), "live structured output should extract at least one draft")
            assertTrue(
                drafts.all { it.title.isNotBlank() },
                "every extracted draft should carry a non-blank title",
            )
        }
    }
}

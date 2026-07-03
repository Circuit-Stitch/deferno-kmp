package com.circuitstitch.deferno.core.agent

/**
 * The outcome of one [InferenceEngine.infer] call. Total over the failure modes the acceptance path
 * cares about (ADR-0027 / #147): callers branch on the type, and no engine failure — malformed model
 * output included — escapes as an unhandled exception.
 *
 * [Failure.detail] is a short, *content-free* diagnostic (a category, a status code, an exception
 * class) safe to log; it must never carry prompt text or model output (the privacy invariant on
 * [InferenceEngine]).
 */
sealed interface InferenceResult<out T : Any> {
    /** The model's output, validated and decoded against the request's [InferenceSchema]. */
    data class Success<out T : Any>(val value: T) : InferenceResult<T>

    sealed interface Failure : InferenceResult<Nothing> {
        val detail: String

        /**
         * The typed reason, for locale-aware rendering (#327): a UI maps it to a localized message
         * while [detail] stays the content-free log string. One [InferenceFailureReason] per subtype.
         */
        val reason: InferenceFailureReason

        /**
         * No credential is configured for the engine — the person hasn't set up inference (the
         * engine choice + opt-in are #150's [[App setting]]). The call made no network request.
         */
        data class NotConfigured(override val detail: String) : Failure {
            override val reason: InferenceFailureReason get() = InferenceFailureReason.NotConfigured
        }

        /**
         * The model answered, but its output failed to validate against the request's schema even
         * after the engine's repair pass — surfaced typed instead of thrown (#147 acceptance).
         */
        data class MalformedOutput(override val detail: String) : Failure {
            override val reason: InferenceFailureReason get() = InferenceFailureReason.MalformedOutput
        }

        /** The endpoint couldn't be reached or answered with an error (network, auth, quota, 5xx). */
        data class Transport(override val detail: String) : Failure {
            override val reason: InferenceFailureReason get() = InferenceFailureReason.Transport
        }
    }
}

/**
 * The typed twin of [InferenceResult.Failure.detail] (#327): a coarse, localizable category the View
 * maps to a gentle message. One value per [InferenceResult.Failure] subtype.
 */
enum class InferenceFailureReason { NotConfigured, MalformedOutput, Transport }

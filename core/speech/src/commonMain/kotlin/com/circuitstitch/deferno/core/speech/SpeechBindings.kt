package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The app-facing [SpeechToText] binding (ADR-0018), an AppScope process-singleton: the
 * [SpeechToTextSelector] over every registered engine (the `Set<SpeechToText>` multibinding contributed
 * `@IntoSet` from each platform's `SpeechBindings`) plus the device-local [SpeechEnginePreference].
 *
 * Speech is a **device capability, identity-independent** — bound at AppScope, **not** AccountScope
 * (ADR-0014), like the secure vault. Structural never-cloud (ADR-0018): the selector can only pick from
 * the engines in this graph, and none is a cloud recognizer.
 *
 * `@Provides` over the abstract [SpeechToText] return type (not the impl) so the merged graph exposes the
 * seam; `@IntoSet` engine contributions feed the `Set<SpeechToText>` constructor argument and never the
 * singular [SpeechToText] binding, so there is no provider collision.
 */
@ContributesTo(AppScope::class)
interface SpeechBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun speechToText(
        engines: Set<SpeechToText>,
        preference: SpeechEnginePreference,
    ): SpeechToText = SpeechToTextSelector(engines, preference)
}

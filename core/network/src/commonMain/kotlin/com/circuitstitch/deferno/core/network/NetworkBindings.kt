package com.circuitstitch.deferno.core.network

import com.circuitstitch.deferno.core.scopes.AppScope
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * AppScope [HttpClient] binding (ADR-0005/0014): the one shared Deferno client per process. It reads
 * the [DefernoEnvironment] (threaded in via [AppComponent]) and the [BearerTokenProvider] (the
 * core:data [AccountBearerTokenProvider], which resolves the Active Account's PAT fresh per request),
 * so a fast Account switch re-points the credential with no client rebuild (ADR-0012/0014).
 *
 * `@Provides` because [DefernoHttpClient] is a factory function, not a constructor.
 */
@ContributesTo(AppScope::class)
interface NetworkBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun httpClient(
        environment: DefernoEnvironment,
        tokenProvider: BearerTokenProvider,
    ): HttpClient = DefernoHttpClient(environment, tokenProvider)
}

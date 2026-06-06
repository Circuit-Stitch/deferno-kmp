package com.circuitstitch.deferno.core.network.platform

import io.ktor.client.engine.HttpClientEngine

/**
 * The production HTTP engine for the current target (ADR-0003 native-per-platform): OkHttp on
 * Android and the desktop/JVM, Darwin (NSURLSession) on iOS. The commonMain client config is
 * engine-agnostic, so the actuals supply only the engine instance.
 *
 * In its own `platform` package because real engine creation runs only on a device / desktop /
 * Apple target and is exercised by integration, not the headless MockEngine gate — the package
 * is excluded from the coverage gate (`CoverageConfig`), like the secure-storage actuals.
 */
internal expect fun platformHttpClientEngine(): HttpClientEngine

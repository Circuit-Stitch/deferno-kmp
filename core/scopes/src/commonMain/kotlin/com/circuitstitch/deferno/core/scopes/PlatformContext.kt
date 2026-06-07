package com.circuitstitch.deferno.core.scopes

/**
 * Opaque per-platform handle the [AppScope] graph is created with (ADR-0008 / ADR-0014): it carries
 * whatever an AppScope binding needs from the host environment — on Android the application
 * `Context` (Keystore vault, SQLCipher driver, SharedPreferences), on desktop the databases
 * directory, and nothing on iOS. The platform app constructs it and hands it to `createAppComponent`;
 * platform `@Provides` in `core:di` extract the concrete handle. Common code neither constructs nor
 * inspects it.
 */
expect class PlatformContext

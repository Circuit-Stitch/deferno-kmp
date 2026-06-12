package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarSocketPath
import com.circuitstitch.deferno.core.sidecar.SidecarTokenSource
import com.circuitstitch.deferno.core.sidecar.unixSocketSidecarClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Desktop (JVM) Sidecar substrate bindings (ADR-0024/0025): the **process-wide [SidecarClient]** —
 * one socket, one handshake — shared by every capability port over it (#119's speech engine now, the
 * #120 permission ports and later capability ports next). It lives here, not in any one capability's
 * bindings, because no single capability owns the client (ADR-0014: bindings live with the module
 * that owns the concept; core/sidecar is deliberately DI-free, so its app-graph wiring sits at the
 * merge site, like [JvmDatabasesDirBindings]).
 *
 * The client dials the per-OS well-known path lazily and re-dials per request; the in-band token is
 * resolved **per handshake** ([SidecarTokenSource]) so a token provisioned after startup (the #122
 * first-run LaunchAgent install) authenticates without an app restart. An unprovisioned token
 * resolves empty → the Helper refuses the handshake → consumers degrade exactly as if no Helper were
 * bound (the selector keeps the whisper floor, ADR-0018).
 */
@ContributesTo(AppScope::class)
interface SidecarBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun sidecarClient(): SidecarClient =
        unixSocketSidecarClient(SidecarSocketPath.default()) { SidecarTokenSource.resolve().orEmpty() }
}

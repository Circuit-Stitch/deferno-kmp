package com.circuitstitch.deferno.core.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Smoke test for the DI scope layering (issue #10). Proves the three scopes compile,
 * create, and resolve on the JVM-fast path (ADR-0006), and that the app -> account ->
 * scene layering and app-scope singleton semantics hold (ADR-0008).
 */
class ScopeGraphTest {
    @Test
    fun resolvesATrivialBindingInEachScope() {
        val app = createAppComponent()
        val account = createAccountComponent(app)
        val scene = createSceneComponent(account)

        assertEquals("app", app.appScaffold.value)
        assertEquals("account", account.accountScaffold.value)
        assertEquals("scene", scene.sceneScaffold.value)
    }

    @Test
    fun parentScopedBindingsResolveThroughChildScopes() {
        val app = createAppComponent()
        val account = createAccountComponent(app)
        val scene = createSceneComponent(account)

        // Layering: a parent-scoped binding is visible to its children.
        assertEquals("app", account.appScaffold.value)
        assertEquals("account", scene.accountScaffold.value)
    }

    @Test
    fun appScopeBindingIsAProcessSingleton() {
        // @SingleIn(AppScope::class) caches one instance within the component — the
        // process-singleton semantics ADR-0008 requires of the data layer.
        val app = createAppComponent()
        assertSame(app.appScaffold, app.appScaffold)
    }

    @Test
    fun sceneScopedBindingsArePerSceneOverASharedAccountGraph() {
        // ADR-0008 G2: presentation is scene-scoped — each window/scene gets its own —
        // while the data + Account graph above it is shared across scenes.
        val account = createAccountComponent(createAppComponent())
        val sceneA = createSceneComponent(account)
        val sceneB = createSceneComponent(account)

        // Each scene has its own scene-scoped binding…
        assertNotSame(sceneA.sceneScaffold, sceneB.sceneScaffold)
        // …but both resolve the one shared Account-scoped binding above them.
        assertSame(sceneA.accountScaffold, sceneB.accountScaffold)
    }
}

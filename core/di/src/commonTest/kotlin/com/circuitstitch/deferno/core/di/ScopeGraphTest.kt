package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Smoke test for the DI scope layering (issue #10). Proves the three scopes compile, create, and
 * resolve on the JVM-fast path (ADR-0006), that the app -> account -> scene layering and app-scope
 * singleton semantics hold (ADR-0008), and — since issue #14 — that the AccountScope's real binding
 * (the Active [Account]) is resolvable per scene scope (ADR-0008 G3).
 */
class ScopeGraphTest {
    private val workAccount = Account(AccountId("work"), "Work")
    private val personalAccount = Account(AccountId("personal"), "Personal")

    @Test
    fun resolvesABindingInEachScope() {
        val app = createAppComponent()
        val account = createAccountComponent(app, workAccount)
        val scene = createSceneComponent(account)

        assertEquals("app", app.appScaffold.value)
        assertEquals(workAccount, account.activeAccount)
        assertEquals("scene", scene.sceneScaffold.value)
    }

    @Test
    fun parentScopedBindingsResolveThroughChildScopes() {
        val app = createAppComponent()
        val account = createAccountComponent(app, workAccount)
        val scene = createSceneComponent(account)

        // Layering: a parent-scoped binding is visible to its children.
        assertEquals("app", account.appScaffold.value)
        assertEquals(workAccount, scene.activeAccount)
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
        val account = createAccountComponent(createAppComponent(), workAccount)
        val sceneA = createSceneComponent(account)
        val sceneB = createSceneComponent(account)

        // Each scene has its own scene-scoped binding…
        assertNotSame(sceneA.sceneScaffold, sceneB.sceneScaffold)
        // …but both resolve the one shared Active Account above them.
        assertSame(sceneA.activeAccount, sceneB.activeAccount)
    }

    @Test
    fun accountContextIsResolvablePerSceneScope() {
        // ADR-0008 G3: the Active Account is scene-scoped, not a hard global — a future window can
        // bind a different Active Account. Two Account graphs over one process (AppComponent), each
        // bound to a different Account, each resolvable at its scene scope.
        val app = createAppComponent()

        val workScene = createSceneComponent(createAccountComponent(app, workAccount))
        val personalScene = createSceneComponent(createAccountComponent(app, personalAccount))

        assertEquals(workAccount, workScene.activeAccount)
        assertEquals(personalAccount, personalScene.activeAccount)
    }
}

package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.scopes.PlatformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Smoke test for the DI scope layering (issue #10). Proves the three scopes compile, create, and
 * resolve on the JVM-fast path (ADR-0006), that the app -> account -> scene layering and app-scope
 * singleton semantics hold (ADR-0008), and — since issue #14 — that the AccountScope's real binding
 * (the Active [Account]) is resolvable per scene scope (ADR-0008 G3).
 *
 * **JVM-only (not commonTest).** Since #68 the [AppComponent] is created from a [PlatformContext]; on
 * the JVM that is a databases dir, but the Android host-test variant would need a Robolectric
 * `Context`. The full graph is compile-time validated on every target by anvil (the merged components
 * cross-compile, including the iOS klibs); this runtime smoke test only needs one fast target. It
 * deliberately touches only the scaffold + Active-Account bindings — not `accountManager` /
 * `authRepository`, which would build the desktop OS-keychain vault (ADR-0014 headless gotcha).
 */
class ScopeGraphTest {
    private val platform = PlatformContext(databasesDir = "build/tmp/scope-graph-test")
    private val environment = DefernoEnvironment.Staging
    private val workAccount = Account(AccountId("work"), "Work")
    private val personalAccount = Account(AccountId("personal"), "Personal")

    private fun app() = createAppComponent(platform, environment)

    @Test
    fun resolvesABindingInEachScope() {
        val app = app()
        val account = createAccountComponent(app, workAccount)
        val scene = createSceneComponent(account)

        assertEquals("app", app.appScaffold.value)
        assertEquals(workAccount, account.activeAccount)
        assertEquals("scene", scene.sceneScaffold.value)
    }

    @Test
    fun parentScopedBindingsResolveThroughChildScopes() {
        val app = app()
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
        val app = app()
        assertSame(app.appScaffold, app.appScaffold)
    }

    @Test
    fun sceneScopedBindingsArePerSceneOverASharedAccountGraph() {
        // ADR-0008 G2: presentation is scene-scoped — each window/scene gets its own —
        // while the data + Account graph above it is shared across scenes.
        val account = createAccountComponent(app(), workAccount)
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
        val app = app()

        val workScene = createSceneComponent(createAccountComponent(app, workAccount))
        val personalScene = createSceneComponent(createAccountComponent(app, personalAccount))

        assertEquals(workAccount, workScene.activeAccount)
        assertEquals(personalAccount, personalScene.activeAccount)
    }
}

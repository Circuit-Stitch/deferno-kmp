package com.circuitstitch.deferno.desktop.chrome

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.feature.signin.SignInComponent
import com.circuitstitch.deferno.shell.AccountSession
import com.circuitstitch.deferno.shell.AuthShellComponent
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.ChromeSpec
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.OverlayRoute
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app-menu routing of #117, on the Linux fast path (the AWT seam is [FakeChromeBackend]). */
class DesktopChromeTest {

    @Test
    fun installedHandlers_routeToTheirActions() {
        val backend = FakeChromeBackend()
        var about = 0
        var preferences = 0
        var quit = 0

        installAppMenuHandlers(
            backend = backend,
            onAbout = { about++ },
            onPreferences = { preferences++ },
            onQuit = { quit++ },
        )

        backend.aboutHandler!!.invoke()
        backend.preferencesHandler!!.invoke()
        backend.quitHandler!!.invoke()

        assertEquals(1, about)
        assertEquals(1, preferences)
        assertEquals(1, quit)
    }

    @Test
    fun withoutANativeAppMenu_nothingInstalls_andNothingThrows() {
        val backend = FakeChromeBackend(appMenuSupported = false)

        installAppMenuHandlers(backend, onAbout = {}, onPreferences = {}, onQuit = {})

        assertNull(backend.aboutHandler)
        assertNull(backend.preferencesHandler)
        assertNull(backend.quitHandler)
    }

    @Test
    fun preferences_opensTheSettingsDestinationOfTheActiveMainShell() {
        val main = FakeMainShell()

        openPreferences(FakeRoot(RootComponent.Child.Main(main)))

        assertEquals(Destination.Settings, main.selected)
    }

    @Test
    fun preferences_isANoOpInTheAuthShell() {
        // Pre-Account there is nothing to open — must be silent, not a crash.
        openPreferences(FakeRoot(RootComponent.Child.Auth(FakeAuthShell)))
    }
}

private class FakeRoot(child: RootComponent.Child) : RootComponent {
    override val stack: Value<ChildStack<*, RootComponent.Child>> =
        MutableValue(ChildStack(configuration = "single", instance = child))
    override val activeChild: StateFlow<RootComponent.Child> = MutableStateFlow(child)
    override val themeSettings: StateFlow<UserSettings> = MutableStateFlow(UserSettings.Default)
    override val activeAccountSession: AccountSession? = null
    override fun onBackClicked(): Boolean = false
    override fun openInbox() = Unit
    override fun openPlan() = Unit
    override fun addTask(title: String) = Unit
}

/** Only [selectDestination] is exercised by the chrome routing; the rest is inert or unused. */
private class FakeMainShell : MainShellComponent {
    var selected: Destination? = null

    override fun selectDestination(destination: Destination) {
        selected = destination
    }

    override val destinations: StateFlow<List<Destination>> = MutableStateFlow(Destination.entries)
    override val stack: Value<ChildStack<*, MainShellComponent.DestinationChild>>
        get() = error("unused by the chrome routing")
    override val activeDestination: StateFlow<MainShellComponent.DestinationChild>
        get() = error("unused by the chrome routing")
    override val overlay: Value<ChildSlot<*, MainShellComponent.OverlayChild>>
        get() = error("unused by the chrome routing")
    override val activeOverlay: StateFlow<MainShellComponent.OverlayChild?>
        get() = error("unused by the chrome routing")
    override val accounts: StateFlow<List<Account>> = MutableStateFlow(emptyList())
    override val activeAccount: StateFlow<Account?> = MutableStateFlow(null)
    override val inboxReadyCount: StateFlow<Int> = MutableStateFlow(0)
    override val chrome: StateFlow<ChromeSpec> = MutableStateFlow(ChromeSpec(title = ""))

    override fun openOverlay(route: OverlayRoute) = Unit
    override fun dismissOverlay() = Unit
    override fun onBack(): Boolean = false
    override fun switchAccount(id: AccountId) = Unit
    override fun signOut() = Unit
}

private object FakeAuthShell : AuthShellComponent {
    override val signIn: SignInComponent
        get() = error("unused by the chrome routing")
}

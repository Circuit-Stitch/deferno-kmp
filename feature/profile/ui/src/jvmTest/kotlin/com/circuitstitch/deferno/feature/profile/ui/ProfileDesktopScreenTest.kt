package com.circuitstitch.deferno.feature.profile.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The desktop Profile render test (#84, cf. #39) — a Compose-Multiplatform UI test on the JVM-fast path
 * (no device) driving [ProfileDesktopScreen] over a fake [ProfileComponent] (no DI graph). It covers the
 * `SignedIn` identity card (initials/name/@handle/Org, Admin chip gating) plus a non-signed-in state, and
 * asserts the always-present Account controls, the retry wiring, and the confirmed sign-out. The Profile
 * state machine itself is unit-tested in feature:profile (ProfileComponentTest).
 */
@OptIn(ExperimentalTestApi::class)
class ProfileDesktopScreenTest {

    @Test
    fun signedIn_rendersIdentity_andAccountControls() = runComposeUiTest {
        val fake = FakeProfileComponent(ProfileState.SignedIn(sampleUser))
        setContent { Themed { ProfileDesktopScreen(fake) } }

        onNodeWithText("Sample User").assertExists()
        onNodeWithText("@sampleuser").assertExists()
        onNodeWithText("u-e4h2qk").assertExists() // the personal Org chip
        // The Account controls are co-located with the identity (CONTEXT.md).
        onNodeWithText("Active account").assertExists()
        onNodeWithText("Personal access token").assertExists()
        // Time zone moved into Profile (#72).
        onNodeWithText("America/Los_Angeles").assertExists()
        onNodeWithText("Sign out").assertExists()
        // The Admin chip shows only for admins; sampleUser.isAdmin == false.
        onNodeWithText("Admin").assertDoesNotExist()
        // /auth/me 0.1 carries no email — the View must not invent one.
        onNodeWithText("Email").assertDoesNotExist()
    }

    @Test
    fun signedInAdmin_showsAdminChip() = runComposeUiTest {
        val fake = FakeProfileComponent(ProfileState.SignedIn(sampleUser.copy(isAdmin = true)))
        setContent { Themed { ProfileDesktopScreen(fake) } }

        onNodeWithText("Admin").assertExists()
    }

    @Test
    fun unavailable_showsRetry_keepsAccountControls_andInvokesOnRetry() = runComposeUiTest {
        val fake = FakeProfileComponent(ProfileState.Unavailable)
        setContent { Themed { ProfileDesktopScreen(fake) } }

        onNodeWithText("Can’t reach Deferno").assertExists()
        // Sign-out works offline (ADR-0009): the Account controls render even when /auth/me failed.
        onNodeWithText("Sign out").assertExists()

        onNodeWithText("Retry").performClick()
        assertEquals(1, fake.retryCount)
    }

    @Test
    fun signOut_confirmsBeforeFiring() = runComposeUiTest {
        val fake = FakeProfileComponent(ProfileState.SignedIn(sampleUser))
        setContent { Themed { ProfileDesktopScreen(fake) } }

        // Opening the confirm dialog (one "Sign out" match) must not sign out yet.
        onNodeWithText("Sign out").performClick()
        assertEquals(0, fake.signOutCount)
        // The dialog's confirm button is the second "Sign out" (the trigger stays composed behind it).
        onAllNodesWithText("Sign out").onLast().performClick()
        assertEquals(1, fake.signOutCount)
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

private val sampleUser = User(
    id = UserId("1"),
    username = "sampleuser",
    displayName = "Sample User",
    role = "member",
    personalOrgId = OrgId("org-1"),
    orgSlug = "u-e4h2qk",
    isAdmin = false,
    consoleUrl = null,
)

/** A fixed-state [ProfileComponent] double — records retry / sign-out calls without a DI graph. */
private class FakeProfileComponent(
    initial: ProfileState,
    override val account: Account = Account(AccountId("work"), "Work"),
) : ProfileComponent {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ProfileState> = _state
    override val timeZone: StateFlow<String?> = MutableStateFlow("America/Los_Angeles")

    var retryCount = 0
        private set
    var signOutCount = 0
        private set

    override fun onRetry() { retryCount++ }
    override fun onSignOut() { signOutCount++ }
}
